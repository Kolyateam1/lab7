package server;

import common.commands.*;
import common.network.Request;
import common.network.Response;
import common.models.City;

import java.util.List;

public class RequestHandler {
    private final CollectionManager collectionManager;

    public RequestHandler(CollectionManager collectionManager) {
        this.collectionManager = collectionManager;
    }

    public Response handle(Request request) {
        Command command = request.getCommand();
        String login = request.getLogin();
        String passwordHash = request.getPasswordHash();

        if (command instanceof RegisterCommand) {
            return handleRegister((RegisterCommand) command);
        }
        if (command instanceof LoginCommand) {
            return handleLogin((LoginCommand) command);
        }

        if (!DatabaseManager.checkUser(login, passwordHash)) {
            return new Response(false, "Неверный логин или пароль. Выполните login или register.");
        }

        try {
            switch (command.getName()) {
                case "add":
                    AddCommand addCmd = (AddCommand) command;
                    boolean added = collectionManager.add(addCmd.getCity(), login);
                    return new Response(added, added ? "Город успешно добавлен" : "Ошибка добавления в БД");

                case "update":
                    UpdateCommand upCmd = (UpdateCommand) command;
                    boolean updated = collectionManager.update(upCmd.getId(), upCmd.getCity(), login);
                    return new Response(updated, updated ? "Город обновлён" : "Не удалось обновить (не ваш город или не существует)");

                case "remove_by_id":
                    RemoveByIdCommand rmCmd = (RemoveByIdCommand) command;
                    boolean removed = collectionManager.removeById(rmCmd.getId(), login);
                    return new Response(removed, removed ? "Город удалён" : "Не удалось удалить (не ваш или не существует)");

                case "clear":
                    collectionManager.clear(login);
                    return new Response(true, "Ваши города удалены");

                case "show":
                    List<City> cities = collectionManager.getSortedByName();
                    return new Response(true, "", cities);

                case "info":
                    return new Response(true, collectionManager.getInfo());

                case "remove_lower":
                    RemoveLowerCommand lowCmd = (RemoveLowerCommand) command;
                    boolean lowRemoved = collectionManager.removeLower(lowCmd.getCity(), login);
                    return new Response(lowRemoved, "Операция выполнена");

                case "execute_script":
                    ExecuteScriptCommand scriptCmd = (ExecuteScriptCommand) command;
                    return new Response(true, "", scriptCmd.getFilename());

                case "reorder":
                    collectionManager.reorder();
                    return new Response(true, "Порядок изменён");

                case "sort":
                    collectionManager.sort();
                    return new Response(true, "Коллекция отсортирована");

                case "remove_any_by_standard_of_living":
                    RemoveAnyByStandardOfLivingCommand anyCmd = (RemoveAnyByStandardOfLivingCommand) command;
                    boolean anyRemoved = collectionManager.removeAnyByStandardOfLiving(anyCmd.getStandardOfLiving(), login);
                    return new Response(anyRemoved, "Удалён элемент с указанным уровнем жизни (если был ваш)");

                case "group_counting_by_governor":
                    return new Response(true, collectionManager.getGroupCounting());

                case "filter_starts_with_name":
                    FilterStartsWithNameCommand filterCmd = (FilterStartsWithNameCommand) command;
                    List<City> filtered = collectionManager.filterStartsWithName(filterCmd.getPrefix());
                    return new Response(true, "", filtered);

                case "help":
                    return new Response(true, getHelpMessage());

                default:
                    return new Response(false, "Неизвестная команда");
            }
        } catch (Exception e) {
            ServerLogger.warning("Ошибка обработки команды: " + e.getMessage());
            return new Response(false, "Ошибка: " + e.getMessage());
        }
    }

    private Response handleRegister(RegisterCommand cmd) {
        boolean ok = DatabaseManager.registerUser(cmd.getLogin(), cmd.getPasswordHash());
        if (ok) {
            return new Response(true, "Регистрация успешна. Теперь выполните login.");
        } else {
            return new Response(false, "Пользователь с таким логином уже существует.");
        }
    }

    private Response handleLogin(LoginCommand cmd) {
        boolean ok = DatabaseManager.checkUser(cmd.getLogin(), cmd.getPasswordHash());
        if (ok) {
            return new Response(true, "Авторизация успешна.");
        } else {
            return new Response(false, "Неверный логин или пароль.");
        }
    }

    private String getHelpMessage() {
        return "Доступные команды:\n" +
                "add - добавить город\n" +
                "update id - обновить город\n" +
                "remove_by_id id - удалить по id\n" +
                "clear - очистить свои города\n" +
                "show - показать все города\n" +
                "info - информация о коллекции\n" +
                "remove_lower - удалить свои города меньше заданного\n" +
                "reorder - обратный порядок\n" +
                "sort - сортировка\n" +
                "remove_any_by_standard_of_living sol - удалить свой город по уровню жизни\n" +
                "group_counting_by_governor - группировка\n" +
                "filter_starts_with_name name - фильтр\n" +
                "help - справка\n" +
                "exit - выход";
    }
}