package server;

import common.models.*;

import java.sql.*;
import java.util.concurrent.ConcurrentLinkedDeque;

public class DatabaseManager {
    private static final String URL = "jdbc:postgresql://pg/studs";
    private static final String USER = "s504862";
    private static final String PASSWORD = "g1TlnMK2EsqEZHcV";

    static {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            ServerLogger.severe("PostgreSQL JDBC драйвер не найден: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    public static void initTables() {
        String createUsers = "CREATE TABLE IF NOT EXISTS users (" +
                "login VARCHAR(50) PRIMARY KEY, " +
                "password_hash VARCHAR(32) NOT NULL)";

        String createCities = "CREATE TABLE IF NOT EXISTS cities (" +
                "id SERIAL PRIMARY KEY, " +
                "name VARCHAR(100) NOT NULL, " +
                "x DOUBLE PRECISION NOT NULL, " +
                "y REAL NOT NULL, " +
                "creation_date DATE NOT NULL, " +
                "area DOUBLE PRECISION NOT NULL, " +
                "population BIGINT NOT NULL, " +
                "meters BIGINT, " +
                "climate VARCHAR(20) NOT NULL, " +
                "government VARCHAR(20) NOT NULL, " +
                "standard_of_living VARCHAR(20), " +
                "governor_age BIGINT NOT NULL, " +
                "owner_login VARCHAR(50) REFERENCES users(login) ON DELETE CASCADE)";

        try (Connection conn = getConnection();
             Statement st = conn.createStatement()) {
            st.execute(createUsers);
            st.execute(createCities);
            ServerLogger.info("Таблицы созданы/проверены");
        } catch (SQLException e) {
            ServerLogger.severe("Ошибка создания таблиц: " + e.getMessage());
        }
    }

    public static boolean registerUser(String login, String passwordHash) {
        String sql = "INSERT INTO users (login, password_hash) VALUES (?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, login);
            stmt.setString(2, passwordHash);
            stmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (e.getSQLState().equals("23505")) {
                ServerLogger.warning("Пользователь " + login + " уже существует");
            } else {
                ServerLogger.warning("Ошибка регистрации: " + e.getMessage());
            }
            return false;
        }
    }

    public static boolean checkUser(String login, String passwordHash) {
        String sql = "SELECT password_hash FROM users WHERE login = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, login);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("password_hash").equals(passwordHash);
            }
            return false;
        } catch (SQLException e) {
            ServerLogger.warning("Ошибка проверки пользователя: " + e.getMessage());
            return false;
        }
    }

    public static long addCity(City city, String ownerLogin) throws SQLException {
        String sql = "INSERT INTO cities (name, x, y, creation_date, area, population, meters, " +
                "climate, government, standard_of_living, governor_age, owner_login) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, city.getName());
            stmt.setDouble(2, city.getCoordinates().getX());
            stmt.setFloat(3, city.getCoordinates().getY());
            stmt.setDate(4, Date.valueOf(city.getCreationDate()));
            stmt.setDouble(5, city.getArea());
            stmt.setLong(6, city.getPopulation());

            if (city.getMetersAboveSeaLevel() != null) {
                stmt.setLong(7, city.getMetersAboveSeaLevel());
            } else {
                stmt.setNull(7, Types.BIGINT);
            }

            stmt.setString(8, city.getClimate().name());
            stmt.setString(9, city.getGovernment().name());

            if (city.getStandardOfLiving() != null) {
                stmt.setString(10, city.getStandardOfLiving().name());
            } else {
                stmt.setNull(10, Types.VARCHAR);
            }

            stmt.setLong(11, city.getGovernor().getAge());
            stmt.setString(12, ownerLogin);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                long newId = rs.getLong(1);
                city.setId(newId);
                city.setOwnerLogin(ownerLogin);
                return newId;
            } else {
                throw new SQLException("Не удалось получить id после вставки");
            }
        }
    }

    public static boolean updateCity(City city, String ownerLogin) throws SQLException {
        String sql = "UPDATE cities SET name=?, x=?, y=?, creation_date=?, area=?, population=?, meters=?, " +
                "climate=?, government=?, standard_of_living=?, governor_age=? " +
                "WHERE id=? AND owner_login=?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, city.getName());
            stmt.setDouble(2, city.getCoordinates().getX());
            stmt.setFloat(3, city.getCoordinates().getY());
            stmt.setDate(4, Date.valueOf(city.getCreationDate()));
            stmt.setDouble(5, city.getArea());
            stmt.setLong(6, city.getPopulation());

            if (city.getMetersAboveSeaLevel() != null) {
                stmt.setLong(7, city.getMetersAboveSeaLevel());
            } else {
                stmt.setNull(7, Types.BIGINT);
            }

            stmt.setString(8, city.getClimate().name());
            stmt.setString(9, city.getGovernment().name());

            if (city.getStandardOfLiving() != null) {
                stmt.setString(10, city.getStandardOfLiving().name());
            } else {
                stmt.setNull(10, Types.VARCHAR);
            }

            stmt.setLong(11, city.getGovernor().getAge());
            stmt.setLong(12, city.getId());
            stmt.setString(13, ownerLogin);

            int updated = stmt.executeUpdate();
            return updated > 0;
        }
    }

    public static boolean deleteCity(long id, String ownerLogin) throws SQLException {
        String sql = "DELETE FROM cities WHERE id = ? AND owner_login = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            stmt.setString(2, ownerLogin);
            int deleted = stmt.executeUpdate();
            return deleted > 0;
        }
    }

    public static ConcurrentLinkedDeque<City> loadAllCities() {
        ConcurrentLinkedDeque<City> cities = new ConcurrentLinkedDeque<>();
        String sql = "SELECT id, name, x, y, creation_date, area, population, meters, " +
                "climate, government, standard_of_living, governor_age, owner_login FROM cities";

        try (Connection conn = getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            long maxId = 0;

            while (rs.next()) {
                City city = new City();
                long id = rs.getLong("id");
                city.setId(id);
                maxId = Math.max(maxId, id);

                city.setName(rs.getString("name"));

                Coordinates coords = new Coordinates(
                        rs.getDouble("x"),
                        rs.getFloat("y")
                );
                city.setCoordinates(coords);

                city.setCreationDate(rs.getDate("creation_date").toLocalDate());
                city.setArea(rs.getDouble("area"));
                city.setPopulation(rs.getLong("population"));

                long meters = rs.getLong("meters");
                if (!rs.wasNull()) {
                    city.setMetersAboveSeaLevel(meters);
                }

                city.setClimate(Climate.valueOf(rs.getString("climate")));
                city.setGovernment(Government.valueOf(rs.getString("government")));

                String sol = rs.getString("standard_of_living");
                if (sol != null && !sol.isEmpty()) {
                    city.setStandardOfLiving(StandardOfLiving.valueOf(sol));
                }

                city.setGovernor(new Human(rs.getLong("governor_age")));
                city.setOwnerLogin(rs.getString("owner_login"));

                cities.add(city);
            }

            City.updateNextId(maxId);
            ServerLogger.info("Загружено городов из БД: " + cities.size());

        } catch (SQLException e) {
            ServerLogger.severe("Ошибка загрузки городов: " + e.getMessage());
        }

        return cities;
    }
}