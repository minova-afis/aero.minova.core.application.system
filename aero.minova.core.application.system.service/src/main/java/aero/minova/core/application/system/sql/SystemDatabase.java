package aero.minova.core.application.system.sql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedList;
import java.util.concurrent.CompletableFuture;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

/**
 * Es ist zur Zeit immer eine SQL-Datenbank. Es ist zur Zeit nicht zwingenderweise dieselbe Datenbank, wie die default-Datenbank dieser Spring-Boot-Anwendung.
 *
 * @author avots
 */
@Component
public class SystemDatabase {
	@Value("${aero_minova_database_url:jdbc:sqlserver://localhost;databaseName=AFIS_HAM}")
	String connectionString;

	@Value("${aero_minova_database_user_name:sa}")
	String userName;
	@Value("${aero_minova_database_user_password:password}")
	String userPassword;

	@Value("${aero.minova.database.kind:mysql}")
	String databaseKind;

	private LinkedList<Connection> freeConnections = new LinkedList<>();

	public synchronized Connection getConnection() {
		try {
			final Connection connection;
			if (freeConnections.isEmpty()) {
				if (databaseKind.equals("postgresql")) {
					Class.forName("org.postgresql.Driver");
				} else {
					Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
				}
				connection = DriverManager.getConnection(connectionString, userName, userPassword);
				connection.setAutoCommit(false);
			} else {
				connection = freeConnections.poll();
			}
			return connection;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public void freeUpConnection(Connection connection) {
		CompletableFuture.runAsync(() -> freeUpConnectionSynchronously(connection));
	}

	private synchronized void freeUpConnectionSynchronously(Connection connection) {
		if (!freeConnections.contains(connection)) {
			try {
				connection.setAutoCommit(false);
				if (connection.isValid(10)) {
					freeConnections.add(connection);
				} else {
					connection.close();
				}
			} catch (SQLException e) {
				throw new RuntimeException(e);
			}
		} else {
			throw new IllegalArgumentException("msg.FreeConnection %" + connection);
		}
	}

	public DataSource getDataSource() throws SQLException {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setDriverClassName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
		dataSource.setUrl(connectionString);
		dataSource.setUsername(userName);
		dataSource.setPassword(userPassword);
		return dataSource;
	}
}