package com.timboudreau.metaupdatecenter;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.name.Named;
import com.mastfrog.acteur.Event;
import com.mastfrog.acteur.auth.Authenticator;
import com.mastfrog.acteur.util.BasicCredentials;
import com.mastfrog.acteur.util.PasswordHasher;
import com.mastfrog.guicy.annotations.Namespace;
import java.io.IOException;

/**
 *
 * @author Tim Boudreau
 */
@Namespace(value = "nbmserver")
class AuthenticatorImpl implements Authenticator {
    private final String hashedPassword;
    private final PasswordHasher hasher;
    private final String userName;
    private final Stats stats;
    private final Provider<Event> event;
    @Inject
    AuthenticatorImpl(@Named(value = "password") String password, PasswordHasher hasher, @Named(value = "admin.user.name") String userName, Stats stats, Provider<Event> event) {
        this.hasher = hasher;
        hashedPassword = hasher.encryptPassword(password);
        this.userName = userName;
        this.stats = stats;
        this.event = event;
    }

    @Override
    public Object[] authenticate(String realm, BasicCredentials credentials) throws IOException {
        if (!userName.equals(credentials.username)) {
            stats.logInvalidCredentials(credentials, event.get());
            return null;
        }
        if (hasher.checkPassword(credentials.password, hashedPassword)) {
            return new Object[]{"ok"};
        }
        stats.logInvalidCredentials(credentials, event.get());
        return null;
    }
}
