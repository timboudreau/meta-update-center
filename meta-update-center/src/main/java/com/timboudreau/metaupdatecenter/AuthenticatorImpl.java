package com.timboudreau.metaupdatecenter;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.auth.Authenticator;
import com.mastfrog.acteur.util.BasicCredentials;
import com.mastfrog.acteur.util.PasswordHasher;
import com.mastfrog.bunyan.Log;
import com.mastfrog.bunyan.Logger;
import com.mastfrog.bunyan.type.Trace;
import static com.timboudreau.metaupdatecenter.UpdateCenterServer.AUTH_LOGGER;
import java.io.IOException;

/**
 *
 * @author Tim Boudreau
 */
@Singleton
class AuthenticatorImpl implements Authenticator {

    final String hashedPassword;
    private final PasswordHasher hasher;
    private final String userName;
    private final Stats stats;
    private final Provider<HttpEvent> event;
    private final Logger logger;

    @Inject
    AuthenticatorImpl(@Named(value = "password") String password, PasswordHasher hasher, @Named(value = "admin.user.name") String userName, Stats stats, Provider<HttpEvent> event,
            @Named(AUTH_LOGGER) Logger logger) {
        this.hasher = hasher;
        hashedPassword = hasher.encryptPassword(password);
        this.userName = userName;
        this.stats = stats;
        this.event = event;
        this.logger = logger;
    }

    @Override
    public Object[] authenticate(String realm, BasicCredentials credentials) throws IOException {
        try (Log<Trace> log = logger.trace("tryAuth")) {
            log.add("realm", realm);
            if (!userName.equals(credentials.username)) {
                log.add("badUserName", credentials.username);
                stats.logInvalidCredentials(credentials, event.get());
                log.add("ok", false);
                return null;
            }
            if (hasher.checkPassword(credentials.password, hashedPassword)) {
                log.add("ok", true);
                return new Object[]{"ok"};
            }
            log.add("badPassword", credentials.password);
            log.add("ok", false);
            stats.logInvalidCredentials(credentials, event.get());
            return null;
        }
    }
}
