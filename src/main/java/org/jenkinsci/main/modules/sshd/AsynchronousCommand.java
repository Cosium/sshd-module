package org.jenkinsci.main.modules.sshd;

import hudson.model.User;
import jenkins.model.Jenkins;
import org.acegisecurity.Authentication;
import org.acegisecurity.context.SecurityContextHolder;
import org.apache.sshd.server.Command;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SessionAware;
import org.apache.sshd.server.session.ServerSession;
import org.jenkinsci.main.modules.sshd.SshCommandFactory.CommandLine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Partial {@link Command} implementation that uses a thread to run a command.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class AsynchronousCommand implements Command, SessionAware {
    private InputStream in;
    private OutputStream out;
    private OutputStream err;
    private ExitCallback callback;
    private CommandLine cmdLine;
    private Thread thread;
    private ServerSession session;
    private Environment environment;

    protected AsynchronousCommand(CommandLine cmdLine) {
        this.cmdLine = cmdLine;
    }

    public void setInputStream(InputStream in) {
        this.in = in;
    }
    
    public void setOutputStream(OutputStream out) {
        this.out = out;
    }

    public void setErrorStream(OutputStream err) {
        this.err = err;
    }

    public InputStream getInputStream() {
        return in;
    }

    public OutputStream getOutputStream() {
        return out;
    }

    public OutputStream getErrorStream() {
        return err;
    }

    public CommandLine getCmdLine() {
        return cmdLine;
    }

    public void setExitCallback(ExitCallback callback) {
        this.callback = callback;
    }

    public ServerSession getSession() {
        return session;
    }

    public void setSession(ServerSession session) {
        this.session = session;
    }

    protected User getCurrentUser() {
        if (Jenkins.getInstance().isUseSecurity())
            return User.get(getSession().getUsername());    // then UserAuthNamedFactory must have done public key auth
        else
            return null;    // not authenticated. anonymous.
    }

    public Environment getEnvironment() {
        return environment;
    }

    public void start(Environment env) throws IOException {
        this.environment = env;
        thread = new Thread(new Runnable() {
            public void run() {
                try {
                    int i;

                    // run the command in the context of the authenticated user
                    Authentication old = SecurityContextHolder.getContext().getAuthentication();
                    User user = getCurrentUser();
                    if (user!=null)
                        SecurityContextHolder.getContext().setAuthentication(user.impersonate());

                    try {
                        i = AsynchronousCommand.this.run();
                    } finally {
                        out.flush(); // working around SSHD-154
                        err.flush();
                        SecurityContextHolder.getContext().setAuthentication(old);
                    }
                    callback.onExit(i);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "SSH command execution failed: "+e.getMessage(), e);
                    callback.onExit(255,e.getMessage());
                }
            }
        });
        thread.setName("SSH command: " + cmdLine.getSingleLine());
        thread.start();
    }

    protected abstract int run() throws Exception;

    public void destroy() {
        if (thread!=null)
            thread.interrupt();
    }

    private static final Logger LOGGER = Logger.getLogger(AsynchronousCommand.class.getName());
}
