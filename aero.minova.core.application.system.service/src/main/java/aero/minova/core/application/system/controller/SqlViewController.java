package aero.minova.core.application.system.controller;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import aero.minova.core.application.system.domain.Column;
import aero.minova.core.application.system.domain.DataType;
import aero.minova.core.application.system.domain.Row;
import aero.minova.core.application.system.domain.Table;
import aero.minova.core.application.system.domain.TableMetaData;
import aero.minova.core.application.system.domain.Value;
import aero.minova.core.application.system.sql.SqlUtils;
import aero.minova.core.application.system.sql.SystemDatabase;
import lombok.val;

@RestController
public class SqlViewController {
	public static final String SQL_IS_NULL = "is null";
	public static final String SQL_IS_NOT_NULL = "is not null";
	public static final String[] SQL_OPERATORS = { "<>", "<=", ">=", "<", ">", "=", "between(", "in(", "not like", "like", SQL_IS_NULL, SQL_IS_NOT_NULL };

	@Autowired
	SystemDatabase systemDatabase;
	Logger logger = LoggerFactory.getLogger(SqlViewController.class);

	@GetMapping(value = "data/index", produces = "application/json")
	public Table getIndexView(@RequestBody Table inputTable) {
		final val connection = systemDatabase.getConnection();
		try {
			final val countQuery = prepareViewString(inputTable, false, 1000, true);
			logger.info("Executing: " + countQuery);
			val preparedCountStatement = connection.prepareCall(countQuery);
			PreparedStatement callableCountStatement = fillPreparedViewString(inputTable, preparedCountStatement);
			ResultSet viewCounter = callableCountStatement.executeQuery();
			viewCounter.next();
			val viewCount = viewCounter.getInt(1);
			val limit = Optional.ofNullable(inputTable.getMetaData())//
					.map(TableMetaData::getLimited)//
					.orElse(Integer.MAX_VALUE);

			final val viewQuery = prepareViewString(inputTable, false, limit, false);
			logger.info("Executing: " + viewQuery);
			val preparedStatement = connection.prepareCall(viewQuery);
			val preparedViewStatement = fillPreparedViewString(inputTable, preparedStatement);
			ResultSet resultSet = preparedViewStatement.executeQuery();

			val result = convertSqlResultToTable(inputTable, resultSet);
			if (limit < viewCount) {
				if (result.getMetaData() == null) {
					result.setMetaData(new TableMetaData());
				}
				result.getMetaData().setLimited(limit);
			}
			return result;
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			systemDatabase.freeUpConnection(connection);
		}
	}

	private PreparedStatement fillPreparedViewString(Table inputTable, CallableStatement preparedStatement) {
		int parameterOffset = 1;

		for (int i = 0; i < inputTable.getColumns().size(); i++) {
			if (!inputTable.getColumns().get(i).getName().equals(Column.AND_FIELD_NAME))
				try {
					val iVal = inputTable.getRows().get(0).getValues().get(i);
					val type = inputTable.getColumns().get(i).getType();

					if (!(iVal == null)) {
						String stringValue = parseType(iVal, type);
						preparedStatement.setString(i + parameterOffset, stringValue);
						logger.info("Filling in: " + parameterOffset + " " + stringValue);
					} else {
						parameterOffset--;
					}
				} catch (Exception e) {
					throw new RuntimeException("Could not parse input parameter with index:" + i, e);
				}
		}
		return preparedStatement;
	}

	private String parseType(Value val, DataType type) {
		String parsedType;
		if (type == DataType.BOOLEAN) {
			parsedType = val.getBooleanValue() + "";
		} else if (type == DataType.DOUBLE) {
			parsedType = val.getStringValue();
		} else if (type == DataType.INSTANT) {
			parsedType = val.getInstantValue().toString();
		} else if (type == DataType.INTEGER) {
			parsedType = val.getStringValue();
		} else if (type == DataType.LONG) {
			parsedType = val.getStringValue();
		} else if (type == DataType.STRING) {
			parsedType = val.getStringValue();
		} else if (type == DataType.ZONED) {
			parsedType = val.getInstantValue().toString();
		} else {
			throw new IllegalArgumentException("Unknown type: " + type.name());
		}
		if (hasOperator(parsedType))
			parsedType = parsedType.substring(getOperatorEndIndex(parsedType));

		return parsedType;
	}

	protected Table convertSqlResultToTable(Table inputTable, ResultSet sqlSet) {
		try {
			Table outputTable = new Table();
			outputTable.setName(inputTable.getName());
			outputTable.setColumns(//
					inputTable.getColumns().stream()//
							.filter(column -> !Objects.equals(column.getName(), Column.AND_FIELD_NAME))//
							.collect(Collectors.toList()));
			while (sqlSet.next()) {
				outputTable.addRow(SqlUtils.convertSqlResultToRow(outputTable, sqlSet, logger, this));
			}
			return outputTable;
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	String prepareViewString(Table params, boolean autoLike, int maxRows) throws IllegalArgumentException {
		return prepareViewString(params, autoLike, maxRows, false);
	}

	/**
	 * Diese Methode stammt ursprünglich aus "ch.minova.ncore.data.sql.SQLTools#prepareViewString". Bereitet einen View-String vor und berücksichtigt eine evtl.
	 * angegebene Maximalanzahl Ergebnisse
	 * 
	 * @param params
	 *            Suchzeilen (z.B. Suchparameter), wobei auch ein Spezialfeld mit dem Namen 'AND' genutzt werden kann, um die Kriterien zu verknüpfen
	 * @param autoLike
	 *            wenn true, dann werden alle String-Parameter, die noch kein % haben, mit einem '%' am Ende versehen
	 * @param maxRows
	 *            maximale Anzahl Ergebnisse (Zeilen), die die Abfrage liefern soll, 0 für unbegrenzt
	 * @param count
	 *            Gibt an ob nur die Anzahl der Ergebniss (Zeilen), gezählt werden sollen.
	 * @return Präparierter View-String, der ausgeführt werden kann
	 * @author wild
	 * @throws IllegalArgumentException
	 */
	String prepareViewString(Table params, boolean autoLike, int maxRows, boolean count) throws IllegalArgumentException {
		final StringBuffer sb = new StringBuffer();
		if (params.getName() == null || params.getName().trim().length() == 0) {
			throw new IllegalArgumentException("Cannot prepare statement with NULL name");
		}

		val outputFormat = params.getColumns().stream()//
				.filter(c -> !Objects.equals(c.getName(), Column.AND_FIELD_NAME))//
				.collect(Collectors.toList());

		if (count) {
			sb.append("select count(1) from ");
		} else {
			if (maxRows > 0) {
				sb.append("select top ").append(maxRows).append(" ");
			}
			if (outputFormat.isEmpty()) {
				sb.append("* from ");
			} else {
				final int paramCount = outputFormat.size();
				for (int i = 0; i < paramCount; i++) {
					sb.append(i == 0 ? outputFormat.get(i).getName() : "," + outputFormat.get(i).getName());
				}
				sb.append(" from ");
			}
		}
		sb.append(params.getName());
		if (params.getColumns().size() > 0 && params.getRows().size() > 0) {
			final String where = prepareWhereClause(params, autoLike);
			sb.append(where);
		}

		return sb.toString();
	}

	/**
	 * @param params
	 *            Suchzeilen (z.B. Suchparameter), wobei auch ein Spezialfeld mit dem Namen 'AND' genutzt werden kann, um die Kriterien zu verknüpfen
	 * @param autoLike
	 *            wenn true, dann werden alle String-Parameter, die noch kein % haben, mit einem '%' am Ende versehen
	 * @return die Where-Klausel für die angegebenen Parameter
	 * @author wild
	 */
	protected String prepareWhereClause(Table params, boolean autoLike) {
		final StringBuffer where = new StringBuffer();
		final boolean hasAndClause;
		// TODO Check size
		val andFields = params.getColumns().stream()//
				.filter(c -> Objects.equals(c.getName(), Column.AND_FIELD_NAME))//
				.collect(Collectors.toList());
		final Column andField;
		if (andFields.isEmpty()) {
			hasAndClause = false;
			andField = null;
		} else {
			hasAndClause = true;
			andField = andFields.get(0);
		}
		val andFieldIndex = params.getColumns().indexOf(andField);
		for (int rowI = 0; rowI < params.getRows().size(); rowI++) {
			final Row r = params.getRows().get(rowI);
			// TODO Nicht annehmen, dass die spezielle &-Spalte die letzte Spalte ist.
			final boolean and;
			if (hasAndClause) {
				and = r.getValues().get(andFieldIndex).getBooleanValue();
			} else {
				and = false;
			}

			// Eine where Zeile aufbauen
			final StringBuffer clause = new StringBuffer();
			COLS: for (int colI = 0; colI < r.getValues().size(); ++colI) {
				val def = r.getValues().get(colI);
				val col = params.getColumns().get(colI);
				if (Column.AND_FIELD_NAME.equalsIgnoreCase(col.getName())) {
					continue COLS;
				}
				if (r.getValues().get(colI) == null) {
					continue COLS;
				}

				final Object valObj = r.getValues().get(colI).getValue();
				String strValue = valObj.toString().trim();
				if (strValue != null && strValue.length() != 0) {
					if (clause.length() > 0) {
						clause.append(" and ");
					}
					clause.append(col.getName());

					// #13193
					if (strValue.equalsIgnoreCase("null") || strValue.equalsIgnoreCase("not null")) {
						clause.append("is ").append(strValue);
					} else {
						if (!hasOperator(strValue)) {
							if (def.getType() == DataType.STRING && strValue.contains("%") || strValue.contains("_")) {
								clause.append(" like");
							} else {
								clause.append(" =");
							}
						} else {
							clause.append(" ").append(strValue.substring(0, getOperatorEndIndex(strValue)));
						}

						clause.append(' ').append("?");
						if (autoLike && valObj instanceof String && def.getType() == DataType.STRING && (!strValue.contains("%")) && (!hasOperator(strValue))) {
							clause.append("%");
						}
					}
				}
			}

			// Wenn es etwas gab, dann fügen wir diese Zeile der kompletten WHERE-clause hinzu
			if (clause.length() > 0) {
				if (where.length() == 0) {
					where.append("\r\nwhere ");
				} else {
					where.append(and ? "\r\n  and " : "\r\n   or ");
				}
				where.append('(').append(clause.toString()).append(')');
			}
		}

		return where.toString();
	}

	/**
	 * Abhängig von dem Feld-Typ, wird der Wert von Kommas umgeben oder nicht
	 */
	protected static String encloseInCommasIfRequired(Value vd, String value) {
		if (vd == null || value == null) {
			return value;
		}

		if (SQL_IS_NULL.equalsIgnoreCase(value.trim()) || SQL_IS_NOT_NULL.equalsIgnoreCase(value.trim())) {
			return value;
		} else if (vd.getType() == DataType.STRING || vd.getType().equals(DataType.INSTANT) || vd.getType().equals(DataType.ZONED)) {
			final int i = getOperatorEndIndex(value);
			if (i > 0) {
				return value.substring(0, i) + " '" + value.substring(i).trim() + "'";
			} else {
				return "'" + value.substring(i).trim() + "'";
			}
		}
		return value;
	}

	/**
	 * Prüft, ob der String einen SQL Operator am Anfang hat
	 * 
	 * @param value
	 * @return
	 */
	protected static boolean hasOperator(String value) {
		return getOperatorEndIndex(value) != 0;
	}

	/**
	 * Wenn es einen Operator gibt, dann liefert die Funktion den Index bis zu dem sich der Operator erstreckt
	 * 
	 * @param value
	 * @return 0, wenn es keinen Operator gibt
	 */
	protected static int getOperatorEndIndex(String value) {
		if (value == null || value.length() == 0) {
			return 0;
		}
		// Wir simulieren einen ltrim, um die Anfangsposition des Operators festzustellen
		String tmp = (value + "_").trim();
		tmp = tmp.toLowerCase(Locale.ENGLISH).substring(0, tmp.length() - 1);
		final int shift = value.length() - tmp.length();
		for (final String sqlOperator : SQL_OPERATORS) {
			if (tmp.startsWith(sqlOperator)) {
				return shift + sqlOperator.length();
			}
		}
		return 0;
	}
}