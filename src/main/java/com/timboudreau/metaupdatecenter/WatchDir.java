/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.timboudreau.metaupdatecenter;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.mastfrog.bunyan.Log;
import com.mastfrog.bunyan.Logger;
import com.mastfrog.bunyan.type.Debug;
import com.mastfrog.bunyan.type.Info;
import com.mastfrog.giulius.ShutdownHookRegistry;
import com.mastfrog.settings.Settings;
import com.mastfrog.util.streams.Streams;
import com.mastfrog.util.time.TimeUtil;
import com.timboudreau.metaupdatecenter.NbmDownloader.DownloadHandler;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.FILE_WATCH_LOGGER;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_KEY_FILE_NOTIFICATION_PROCESS_DELAY_SECONDS;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.SETTINGS_KEY_WATCH_DIR;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.xml.xpath.XPathExpressionException;

/**
 * Allows a local folder
 *
 * @author Tim Boudreau
 */
final class WatchDir implements Runnable {

    private final NbmDownloader processor;

    private final ByteBufAllocator alloc;
    private volatile boolean shuttingDown;
    private final Thread.UncaughtExceptionHandler ueh;
    private final WatchService watchService;
    private final DelayQueue<DelayKey> queue;
    private final Duration delay;
    private final Logger logger;
    private final ModuleSet set;

    @Inject
    public WatchDir(Settings settings, ShutdownHookRegistry reg, NbmDownloader processor,
            ByteBufAllocator alloc, Thread.UncaughtExceptionHandler ueh, @Named(FILE_WATCH_LOGGER) Logger logger,
            ModuleSet set) throws IOException {
        this.processor = processor;
        this.alloc = alloc;
        this.ueh = ueh;
        this.logger = logger;
        this.set = set;
        String pth = settings.getString(SETTINGS_KEY_WATCH_DIR);
        Duration delay = null;
        ExecutorService threadPool = null;
        WatchService watchService = null;
        DelayQueue<DelayKey> queue = null;
        Path path = null;
        try (Log<Info> log = logger.info("startup")) {
            if (pth != null) {
                try {
                    path = Paths.get(pth).normalize().toAbsolutePath();
                    log.add("path", path.toString());
                    if (Files.exists(path) && Files.isDirectory(path)) {
                        log.add("exists", true);
                        System.err.println("Will watch " + path + " for incoming modules");
                        watchService = FileSystems.getDefault().newWatchService();
                        long seconds = settings.getLong(SETTINGS_KEY_FILE_NOTIFICATION_PROCESS_DELAY_SECONDS, 10);
                        delay = Duration.ofSeconds(seconds);
                        reg.add(watchService);
                        path.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
                        queue = new DelayQueue<>();
                        threadPool = Executors.newFixedThreadPool(3);
                        log.add("watching", true).add("delay", seconds);
                    } else {
                        log.add("exists", false).add("watching", false);
                    }
                } catch (Exception e) {
                    log.add(e);
                }
            } else {
                log.add("path", "<none>").add("watching", false);
            }
        }
        this.watchService = watchService;
        this.queue = queue;
        this.delay = delay;
        if (threadPool != null && queue != null && path != null) {
            threadPool.submit(new Watcher(path));
            threadPool.submit(new Processor());
            threadPool.submit(new InitialScan(path));
            reg.add(threadPool);
            // Add this last since hooks are run in reverse order - we want shuttingDown
            // to be true before anything else is interrupted
            reg.add(this);
        }
    }

    private final class InitialScan implements Runnable {

        private final Path path;

        InitialScan(Path path) {
            this.path = path;
        }

        @Override
        public void run() {
            try {
                Files.walk(path, FileVisitOption.FOLLOW_LINKS).filter(child -> {
                    return child.getFileName().toString().endsWith(".nbm");
                }).forEach(child -> {
                    try (Log<Info> log = logger.info("initial-scan")) {
                        log.add("path", child.toString());
                        try {
                            if (processingFailed(child)) {
                                log.add("status", "alreadyFailed");
                            } else if (alreadyProcessed(child)) {
                                log.add("status", "alreadyProcessed");
                            }
                            if (!processingFailed(child) && !alreadyProcessed(child)) {
                                DelayKey key = new DelayKey(child, delay.toMillis());
                                if (!shuttingDown) {
                                    queue.offer(key);
                                }
                                log.add("status", "queued");
                            }
                        } catch (IOException ex) {
                            log.add(ex);
                            ueh.uncaughtException(Thread.currentThread(), ex);
                        }
                    }
                });

            } catch (IOException ioe) {
                ueh.uncaughtException(Thread.currentThread(), ioe);
            }
        }
    }

    @Override
    public void run() {
        shuttingDown = true;
    }

    private Path markerFile(Path path) {
        return path.getParent().resolve(path.getFileName() + ".processed");
    }

    private long markerFileLastModified(Path path) throws IOException {
        Path markerFile = markerFile(path);
        if (!Files.exists(markerFile)) {
            return -1;
        } else {
            return Files.getLastModifiedTime(path).to(TimeUnit.MILLISECONDS);
        }
    }

    private boolean alreadyProcessed(Path path) throws IOException {
        long lastModified = Files.getLastModifiedTime(path).to(TimeUnit.MILLISECONDS);
        return lastModified <= markerFileLastModified(path);
    }

    private Path failedFile(Path path) {
        return path.getParent().resolve(path.getFileName() + ".failed");
    }

    private boolean processingFailed(Path path) throws IOException {
        Path failedFile = path.getParent().resolve(path.getFileName() + ".failed");
        if (!Files.exists(failedFile)) {
            return false;
        } else {
            long fileLm = Files.getLastModifiedTime(path).to(TimeUnit.MILLISECONDS);
            long failedLm = Files.getLastModifiedTime(failedFile).to(TimeUnit.MILLISECONDS);
            return fileLm <= failedLm;
        }
    }

    private void processOne(Path path) throws IOException {
        if (!processingFailed(path) && !alreadyProcessed(path)) {
            long length = Files.size(path);
            ByteBuf buf = alloc.buffer((int) length);
            try (ByteBufOutputStream out = new ByteBufOutputStream(buf)) {
                try (InputStream in = Files.newInputStream(path, StandardOpenOption.READ)) {
                    Streams.copy(in, out);
                }
            }
            DownloadHandler dh = new DownloadHandler() {
                @Override
                public boolean onResponse(HttpResponseStatus status, HttpHeaders headers) {
                    throw new AssertionError("Shouldn't get here.");
                }

                @Override
                public void onModuleDownload(InfoFile module, InputStream bytes, String hash, String willBeNull) {
                    if (module == null) {
                        // duplicate
                        logger.info("module-processed").add("origPath", path.toString())
                                .add("duplicate", true).add("success", false).close();
                        return;
                    }
                    try (Log<Info> log = logger.info("module-processed")) {
                        log.add("origPath", path.toString())
                                .add("cnb", module.getModuleCodeName())
                                .add("specVersion", module.getModuleVersion().toString())
                                .add("implVersion", module.getImplementationVersion())
                                .add("hash", hash)
                                .add("duplicate", false);
                        buf.resetReaderIndex();
                        ZonedDateTime when = TimeUtil.fromUnixTimestamp(Files.getLastModifiedTime(path).to(TimeUnit.MILLISECONDS));
                        ModuleItem item = set.add(module, new ByteBufInputStream(buf), path.toUri().toURL().toString(), hash, false, when);
                        log.add("success", true);
                        Files.createFile(markerFile(path));
                    } catch (XPathExpressionException | IOException ex) {
                        ueh.uncaughtException(Thread.currentThread(), ex);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    Path failFile = failedFile(path);
                    System.out.println("WRITE FAIL FILE " + failFile);
                    try (PrintStream ps = new PrintStream(Files.newOutputStream(failFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
                        ps.println(TimeUtil.toHttpHeaderFormat(TimeUtil.nowGMT()));
                        ps.println(path.toString());
                        t.printStackTrace(ps);
                        ps.println("---------------------------------------------------------\n\n");
                    } catch (IOException ex) {
                        ueh.uncaughtException(Thread.currentThread(), t);
                    }
                }
            };
            processor.handleDownloadedNBM(buf, dh, null);
        }
    }

    private class Watcher implements Runnable {

        private final Path dir;

        Watcher(Path dir) {
            this.dir = dir;
        }

        @Override
        public void run() {
            Thread.currentThread().setName("new-nbm-dir-watcher");
            WatchKey key;
            for (;;) {
                try {
                    key = watchService.take();
                    for (WatchEvent<?> event : key.pollEvents()) {
                        try (Log<Debug> log = logger.debug("watch")) {
                            Path path = (Path) event.context();
                            path = dir.resolve(path);
                            if (path.toString().endsWith("nbm")) {
                                log.add("path", path.toString());
                                DelayKey delayKey = new DelayKey(path, delay.toMillis());
                                if (queue.contains(delayKey)) {
                                    queue.remove(delayKey);
                                    queue.add(delayKey);
                                    log.add("requeued", true);
                                    continue;
                                }
                                queue.add(delayKey);
                                log.add("submitted", true);
                                log.add("delay", delayKey.getDelay(TimeUnit.MILLISECONDS));
                            }
                        }
                    }
                    key.reset();
                } catch (InterruptedException | ClosedWatchServiceException ex) {
                    if (shuttingDown) {
                        logger.info("watcher-thread-exist").close();
                        return;
                    } else {
                        ueh.uncaughtException(Thread.currentThread(), ex);
                    }
                } catch (Exception ex) {
                    if (shuttingDown) {
                        logger.info("watcher-thread-exist").close();
                        return;
                    } else {
                        ueh.uncaughtException(Thread.currentThread(), ex);
                    }
                }
            }
        }
    }

    private class Processor implements Runnable {

        @Override
        public void run() {
            Thread.currentThread().setName("new-nbm-dir-processor");
            DelayKey key;
            for (;;) {
                try {
                    key = queue.take();
                    if (key != null) {
                        try (Log<Info> log = logger.info("process")) {
                            log.add("path", key.pth.toString());
                            if (!key.isValid()) {
                                log.add("valid", false);
                                continue;
                            }
                            log.add("valid", true);
                            if (key.isModified()) {
                                log.add("modified-since-enqueueing", true);
                                queue.offer(key.reset());
                                continue;
                            }
                            log.add("modified-since-enqueueing", false);
                            Path path = key.path();
                            processOne(path);
                        }
                    }
                } catch (InterruptedException ex) {
                    if (shuttingDown) {
                        logger.info("processor-thread-exist").close();
                        return;
                    } else {
                        ueh.uncaughtException(Thread.currentThread(), ex);
                    }
                } catch (Exception ex) {
                    ueh.uncaughtException(Thread.currentThread(), ex);
                }
            }
        }
    }

    private static class DelayKey implements Delayed {

        long created = System.currentTimeMillis();
        private final Path pth;
        private final long delay;
        private long lastModified;

        DelayKey(Path pth, long delay) throws IOException {
            this.pth = pth;
            this.delay = delay;
            lastModified = Files.getLastModifiedTime(pth).to(TimeUnit.MILLISECONDS);
        }

        public Path path() {
            return pth;
        }

        public boolean isValid() {
            return Files.exists(pth);
        }

        public boolean isModified() throws IOException {
            return isValid() && lastModified != Files.getLastModifiedTime(pth).to(TimeUnit.MILLISECONDS);
        }

        public DelayKey reset() throws IOException {
            return new DelayKey(pth, delay);
        }

        public String toString() {
            return pth + " delay " + getDelay(TimeUnit.MILLISECONDS);
        }

        @Override
        public long getDelay(TimeUnit unit) {
            long expiresAt = created + delay;
            return expiresAt - System.currentTimeMillis();
        }

        @Override
        public int compareTo(Delayed o) {
            long a = created;
            long b = ((DelayKey) o).created;
            return a > b ? 1 : a == b ? 0 : -1;
        }

        public boolean equals(Object o) {
            return o instanceof DelayKey && ((DelayKey) o).pth.toString().equals(pth.toString());
        }

        public int hashCode() {
            return pth.toString().hashCode();
        }
    }
}
