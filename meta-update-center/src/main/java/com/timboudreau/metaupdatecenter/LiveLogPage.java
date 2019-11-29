package com.timboudreau.metaupdatecenter;

import com.google.common.base.Optional;
import com.google.common.net.MediaType;
import com.google.inject.Inject;
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.Closables;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.annotations.HttpCall;
import com.mastfrog.acteur.annotations.Precursors;
import static com.mastfrog.acteur.headers.Headers.CONTENT_TYPE;
import static com.mastfrog.acteur.headers.Method.GET;
import com.mastfrog.acteur.preconditions.Authenticated;
import com.mastfrog.acteur.preconditions.Description;
import com.mastfrog.acteur.preconditions.Methods;
import com.mastfrog.acteur.preconditions.ParametersMustBeNumbersIfPresent;
import com.mastfrog.acteur.preconditions.Path;
import com.mastfrog.acteur.spi.ApplicationControl;
import com.mastfrog.util.streams.ContinuousLineStream;
import com.mastfrog.util.streams.ContinuousStringStream;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.DefaultHttpContent;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *
 * @author Tim Boudreau
 */
@HttpCall(scopeTypes = {File.class, Long.class})
@Path("/livelog")
@Authenticated
@Methods(GET)
@ParametersMustBeNumbersIfPresent("offset")
@Precursors({LogPage.CheckLogEnabled.class, LogPage.CheckLogFileReadable.class})
@Description("Get the server log, leaving the connection open and flushing new "
        + "log lines as they arrive, for use with curl | bunyan")
public class LiveLogPage extends Acteur {

    @Inject
    LiveLogPage() {
        setChunked(true);
        ok();
        add(CONTENT_TYPE, MediaType.JSON_UTF_8);
        setResponseBodyWriter(LiveResponseWriter.class);
    }

    static class LiveResponseWriter implements ChannelFutureListener {

        private final ContinuousLineStream stream;
        private final Timer timer = new Timer();
        private final ApplicationControl control;

        @Inject
        LiveResponseWriter(File file, Closables clos, Charset charset, ApplicationControl control, HttpEvent evt) throws IOException {
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            FileChannel channel = raf.getChannel();
            clos.add(raf);
            clos.add(channel);
            ContinuousStringStream strings = new ContinuousStringStream(channel, 1024);
            stream = new ContinuousLineStream(strings, charset.newDecoder(), 1024);
            Optional<Long> offsetOpt = evt.longUrlParameter("offset");
            if (offsetOpt.isPresent()) {
                stream.position(Math.min(stream.available(), offsetOpt.get()));
                // get to the first real line start
                stream.next();
            }
            clos.add(stream);
            clos.add(timer);
            this.control = control;
        }

        final AtomicInteger in = new AtomicInteger();

        @Override
        public void operationComplete(ChannelFuture f) throws Exception {
            if (f.channel().isOpen()) {
                write(f.channel());
            }
        }

        private AtomicBoolean lastFlushed = new AtomicBoolean(true);
        final ChannelFutureListener onFlush = new ChannelFutureListener() {

            @Override
            public void operationComplete(ChannelFuture f) throws Exception {
                if (in.getAndIncrement() == 0) {
                    timer.scheduleAtFixedRate(new TT(f.channel()), 3000, 10000);
                }
                lastFlushed.set(true);
            }

        };

        void write(Channel channel) throws IOException {
            if (!lastFlushed.get()) {
                return;
            }
            CharSequence next;
            ChannelFuture f = null;
            while ((next = stream.nextLine()) != null) {
                ByteBuf buf = channel.alloc().buffer();
                ByteBufUtil.writeUtf8(buf, next);
                buf.writeByte((byte) '\n');
                f = channel.writeAndFlush(new DefaultHttpContent(buf));
            }
            if (f != null) {
                f.addListener(onFlush);
            }
        }

        private class TT extends TimerTask {

            private final Channel channel;

            private TT(Channel channel) {
                this.channel = channel;
            }

            @Override
            public void run() {
                try {
                    write(channel);
                } catch (Exception ex) {
                    control.internalOnError(ex);
                }
            }
        }
    }
}
