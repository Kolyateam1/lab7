package common.commands;

public class ExecuteScriptCommand implements Command {
    private static final long serialVersionUID = 1L;
    private final String filename;

    public ExecuteScriptCommand(String filename) {
        this.filename = filename;
    }

    public String getFilename() {
        return filename;
    }

    @Override
    public String getName() {
        return "execute_script";
    }
}