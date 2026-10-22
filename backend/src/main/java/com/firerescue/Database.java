package com.firerescue;

import java.io.InputStream;
import java.sql.*;
import java.util.Properties;

public final class Database {
    private static final Properties PROPERTIES = new Properties();

    static {
        try (InputStream in = Database.class.getClassLoader().getResourceAsStream("db.properties")) {
            if (in == null) throw new IllegalStateException("db.properties is missing");
            PROPERTIES.load(in);
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Database() {}

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(
            PROPERTIES.getProperty("db.url"),
            PROPERTIES.getProperty("db.username"),
            PROPERTIES.getProperty("db.password")
        );
    }
}
