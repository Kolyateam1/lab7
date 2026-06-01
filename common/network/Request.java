package common.network;

import common.commands.Command;
import java.io.Serializable;

public class Request implements Serializable {
    private static final long serialVersionUID = 2L;
    private final Command command;
    private final String login;
    private final String passwordHash;

    public Request(Command command, String login, String passwordHash) {
        this.command = command;
        this.login = login;
        this.passwordHash = passwordHash;
    }

    public Command getCommand() { return command; }
    public String getLogin() { return login; }
    public String getPasswordHash() { return passwordHash; }
}