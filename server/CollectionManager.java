package server;

import common.models.City;
import common.models.StandardOfLiving;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

public class CollectionManager {
    private final ConcurrentLinkedDeque<City> collection;
    private final LocalDateTime initDate;

    public CollectionManager() {
        this.collection = DatabaseManager.loadAllCities();
        this.initDate = LocalDateTime.now();
    }

    public synchronized boolean add(City city, String ownerLogin) {
        try {
            DatabaseManager.addCity(city, ownerLogin);
            collection.add(city);
            return true;
        } catch (SQLException e) {
            ServerLogger.warning("Не удалось добавить город в БД: " + e.getMessage());
            return false;
        }
    }

    public synchronized boolean update(long id, City newCity, String ownerLogin) {
        City old = findById(id);
        if (old == null || !old.getOwnerLogin().equals(ownerLogin)) return false;

        newCity.setId(id);
        newCity.setOwnerLogin(ownerLogin);
        try {
            if (DatabaseManager.updateCity(newCity, ownerLogin)) {
                collection.remove(old);
                collection.add(newCity);
                return true;
            }
        } catch (SQLException e) {
            ServerLogger.warning("Ошибка обновления в БД: " + e.getMessage());
        }
        return false;
    }

    public synchronized boolean removeById(long id, String ownerLogin) {
        City city = findById(id);
        if (city == null || !city.getOwnerLogin().equals(ownerLogin)) return false;

        try {
            if (DatabaseManager.deleteCity(id, ownerLogin)) {
                collection.remove(city);
                return true;
            }
        } catch (SQLException e) {
            ServerLogger.warning("Ошибка удаления из БД: " + e.getMessage());
        }
        return false;
    }

    public synchronized boolean removeLower(City comparator, String ownerLogin) {
        List<City> toRemove = collection.stream()
                .filter(c -> c.getOwnerLogin().equals(ownerLogin) && c.compareTo(comparator) < 0)
                .collect(Collectors.toList());

        boolean allOk = true;
        for (City c : toRemove) {
            try {
                if (DatabaseManager.deleteCity(c.getId(), ownerLogin)) {
                    collection.remove(c);
                } else {
                    allOk = false;
                }
            } catch (SQLException e) {
                ServerLogger.warning("Ошибка удаления removeLower: " + e.getMessage());
                allOk = false;
            }
        }
        return allOk;
    }

    public synchronized boolean removeAnyByStandardOfLiving(StandardOfLiving sol, String ownerLogin) {
        City toRemove = collection.stream()
                .filter(c -> c.getOwnerLogin().equals(ownerLogin) &&
                        (sol == null ? c.getStandardOfLiving() == null : sol.equals(c.getStandardOfLiving())))
                .findFirst()
                .orElse(null);

        if (toRemove == null) return false;

        try {
            if (DatabaseManager.deleteCity(toRemove.getId(), ownerLogin)) {
                collection.remove(toRemove);
                return true;
            }
        } catch (SQLException e) {
            ServerLogger.warning("Ошибка удаления removeAny: " + e.getMessage());
        }
        return false;
    }

    public synchronized void clear(String ownerLogin) {
        List<City> userCities = collection.stream()
                .filter(c -> c.getOwnerLogin().equals(ownerLogin))
                .collect(Collectors.toList());

        for (City c : userCities) {
            try {
                if (DatabaseManager.deleteCity(c.getId(), ownerLogin)) {
                    collection.remove(c);
                }
            } catch (SQLException e) {
                ServerLogger.warning("Ошибка удаления при clear: " + e.getMessage());
            }
        }
    }

    public boolean containsId(long id) {
        return collection.stream().anyMatch(c -> c.getId() == id);
    }

    public City findById(long id) {
        return collection.stream().filter(c -> c.getId() == id).findFirst().orElse(null);
    }

    public List<City> getSortedByName() {
        return collection.stream()
                .sorted(Comparator.comparing(City::getName))
                .collect(Collectors.toList());
    }

    public String getInfo() {
        return "Тип коллекции: " + collection.getClass().getName() + "\n" +
                "Дата инициализации: " + initDate + "\n" +
                "Количество элементов: " + collection.size();
    }

    public String getGroupCounting() {
        Map<Long, Long> groups = collection.stream()
                .collect(Collectors.groupingBy(
                        c -> c.getGovernor().getAge(),
                        Collectors.counting()
                ));

        if (groups.isEmpty()) return "Коллекция пуста";

        return groups.entrySet().stream()
                .map(e -> "Возраст " + e.getKey() + ": " + e.getValue() + " городов")
                .collect(Collectors.joining("\n"));
    }

    public List<City> filterStartsWithName(String prefix) {
        if (prefix == null || prefix.isEmpty()) return new ArrayList<>();

        return collection.stream()
                .filter(c -> c.getName().toLowerCase().startsWith(prefix.toLowerCase()))
                .collect(Collectors.toList());
    }

    public void reorder() {
        List<City> list = new ArrayList<>(collection);
        Collections.reverse(list);
        collection.clear();
        collection.addAll(list);
    }

    public void sort() {
        List<City> list = new ArrayList<>(collection);
        Collections.sort(list);
        collection.clear();
        collection.addAll(list);
    }
}