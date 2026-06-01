package common.commands;

public class RegisterCommand implements Command {
    private static final long serialVersionUID = 1L;
    private final String login;
    private final String passwordHash;

    public RegisterCommand(String login, String passwordHash) {
        this.login = login;
        this.passwordHash = passwordHash;
    }

    public String getLogin() { return login; }
    public String getPasswordHash() { return passwordHash; }

    @Override
    public String getName() { return "register"; }
}