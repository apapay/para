/*
 * Copyright 2013-2016 Erudika. https://erudika.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * For issues and patches go to: https://github.com/erudika
 */
package com.erudika.para.persistence;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Index;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.DeleteTableRequest;
import com.amazonaws.services.dynamodbv2.model.DescribeTableResult;
import com.amazonaws.services.dynamodbv2.model.GlobalSecondaryIndex;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.ListTablesResult;
import com.amazonaws.services.dynamodbv2.model.Projection;
import com.amazonaws.services.dynamodbv2.model.ProjectionType;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType;
import com.amazonaws.services.dynamodbv2.model.TableDescription;
import com.amazonaws.services.dynamodbv2.model.UpdateTableRequest;
import com.erudika.para.DestroyListener;
import com.erudika.para.Para;
import com.erudika.para.utils.Config;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper utilities for connecting to AWS DynamoDB.
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public final class AWSDynamoUtils {

	private static AmazonDynamoDBClient ddbClient;
	private static DynamoDB ddb;
	private static final String LOCAL_ENDPOINT = "http://localhost:8000";
	private static final String ENDPOINT = "dynamodb.".concat(Config.AWS_REGION).concat(".amazonaws.com");
	private static final Logger logger = LoggerFactory.getLogger(AWSDynamoUtils.class);

	/**
	 * The name of the shared table. Default is "0".
	 */
	public static final String SHARED_TABLE = Config.getConfigParam("shared_table_name", "0");

	private AWSDynamoUtils() { }

	/**
	 * Returns a client instance for AWS DynamoDB.
	 * @return a client that talks to DynamoDB
	 */
	public static AmazonDynamoDBClient getClient() {
		if (ddbClient != null) {
			return ddbClient;
		}

		if (Config.IN_PRODUCTION) {
			ddbClient = new AmazonDynamoDBClient(new BasicAWSCredentials(Config.AWS_ACCESSKEY, Config.AWS_SECRETKEY));
			ddbClient.setEndpoint(ENDPOINT);
		} else {
			ddbClient = new AmazonDynamoDBClient(new BasicAWSCredentials("local", "null"));
			ddbClient.setEndpoint(LOCAL_ENDPOINT);
		}

		if (!existsTable(Config.APP_NAME_NS)) {
			createTable(Config.APP_NAME_NS);
		}

		ddb = new DynamoDB(ddbClient);

		Para.addDestroyListener(new DestroyListener() {
			public void onDestroy() {
				shutdownClient();
			}
		});

		return ddbClient;
	}

	/**
	 * Stops the client and releases resources.
	 * <b>There's no need to call this explicitly!</b>
	 */
	protected static void shutdownClient() {
		if (ddbClient != null) {
			ddbClient.shutdown();
			ddbClient = null;
			ddb.shutdown();
			ddb = null;
		}
	}

	/**
	 * Checks if the main table exists in the database.
	 * @param appid name of the {@link com.erudika.para.core.App}
	 * @return true if the table exists
	 */
	public static boolean existsTable(String appid) {
		if (StringUtils.isBlank(appid)) {
			return false;
		}
		try {
			DescribeTableResult res = getClient().describeTable(getTableNameForAppid(appid));
			return res != null;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Creates the main table.
	 * @param appid name of the {@link com.erudika.para.core.App}
	 * @return true if created
	 */
	public static boolean createTable(String appid) {
		return createTable(appid, 2L, 1L);
	}

	/**
	 * Creates a table in AWS DynamoDB.
	 * @param appid name of the {@link com.erudika.para.core.App}
	 * @param readCapacity read capacity
	 * @param writeCapacity write capacity
	 * @return true if created
	 */
	public static boolean createTable(String appid, long readCapacity, long writeCapacity) {
		if (StringUtils.isBlank(appid)) {
			return false;
		} else if (StringUtils.containsWhitespace(appid)) {
			logger.warn("DynamoDB table name contains whitespace. The name '{}' is invalid.", appid);
			return false;
		} else if (existsTable(appid)) {
			logger.warn("DynamoDB table '{}' already exists.", appid);
			return false;
		}
		try {
			getClient().createTable(new CreateTableRequest().withTableName(getTableNameForAppid(appid)).
					withKeySchema(new KeySchemaElement(Config._KEY, KeyType.HASH)).
					withAttributeDefinitions(new AttributeDefinition(Config._KEY, ScalarAttributeType.S)).
					withProvisionedThroughput(new ProvisionedThroughput(readCapacity, writeCapacity)));
		} catch (Exception e) {
			logger.error(null, e);
			return false;
		}
		return true;
	}

	/**
	 * Updates the table settings (read and write capacities).
	 * @param appid name of the {@link com.erudika.para.core.App}
	 * @param readCapacity read capacity
	 * @param writeCapacity write capacity
	 * @return true if updated
	 */
	public static boolean updateTable(String appid, long readCapacity, long writeCapacity) {
		if (StringUtils.isBlank(appid) || StringUtils.containsWhitespace(appid)) {
			return false;
		}
		try {
			Map<String, Object> dbStats = getTableStatus(appid);
			String status = (String) dbStats.get("status");
			// AWS throws an exception if the new read/write capacity values are the same as the current ones
			if (!dbStats.isEmpty() && "ACTIVE".equalsIgnoreCase(status)) {
				getClient().updateTable(new UpdateTableRequest().withTableName(getTableNameForAppid(appid)).
						withProvisionedThroughput(new ProvisionedThroughput(readCapacity, writeCapacity)));
				return true;
			}
		} catch (Exception e) {
			logger.error(null, e);
		}
		return false;
	}

	/**
	 * Deletes the main table from AWS DynamoDB.
	 * @param appid name of the {@link com.erudika.para.core.App}
	 * @return true if deleted
	 */
	public static boolean deleteTable(String appid) {
		if (StringUtils.isBlank(appid) || !existsTable(appid)) {
			return false;
		}
		try {
			getClient().deleteTable(new DeleteTableRequest().withTableName(getTableNameForAppid(appid)));
		} catch (Exception e) {
			logger.error(null, e);
			return false;
		}
		return true;
	}

	/**
	 * Creates a table in AWS DynamoDB which will be shared between apps.
	 * @param readCapacity read capacity
	 * @param writeCapacity write capacity
	 * @return true if created
	 */
	public static boolean createSharedTable(long readCapacity, long writeCapacity) {
		if (StringUtils.isBlank(SHARED_TABLE) || StringUtils.containsWhitespace(SHARED_TABLE) ||
				existsTable(SHARED_TABLE)) {
			return false;
		}
		try {
			GlobalSecondaryIndex secIndex = new GlobalSecondaryIndex().
					withIndexName(getSharedIndexName()).
					withProvisionedThroughput(new ProvisionedThroughput().
							withReadCapacityUnits(1L).
							withWriteCapacityUnits(1L)).
					withProjection(new Projection().withProjectionType(ProjectionType.ALL)).
					withKeySchema(new KeySchemaElement().withAttributeName(Config._APPID).withKeyType(KeyType.HASH),
							new KeySchemaElement().withAttributeName(Config._TIMESTAMP).withKeyType(KeyType.RANGE));

			getClient().createTable(new CreateTableRequest().withTableName(getTableNameForAppid(SHARED_TABLE)).
					withKeySchema(new KeySchemaElement(Config._KEY, KeyType.HASH)).
					withAttributeDefinitions(new AttributeDefinition(Config._KEY, ScalarAttributeType.S),
							new AttributeDefinition(Config._APPID, ScalarAttributeType.S),
							new AttributeDefinition(Config._TIMESTAMP, ScalarAttributeType.S)).
					withGlobalSecondaryIndexes(secIndex).
					withProvisionedThroughput(new ProvisionedThroughput(readCapacity, writeCapacity)));
		} catch (Exception e) {
			logger.error(null, e);
			return false;
		}
		return true;
	}

	/**
	 * Gives basic information about a DynamoDB table (status, creation date, size).
	 * @param appid name of the {@link com.erudika.para.core.App}
	 * @return a map
	 */
	public static Map<String, Object> getTableStatus(final String appid) {
		if (StringUtils.isBlank(appid)) {
			return Collections.emptyMap();
		}
		try {
			final TableDescription td = getClient().describeTable(getTableNameForAppid(appid)).getTable();
			return new HashMap<String, Object>() {
				{
					put("id", appid);
					put("status", td.getTableStatus());
					put("created", td.getCreationDateTime().getTime());
					put("sizeBytes", td.getTableSizeBytes());
					put("itemCount", td.getItemCount());
					put("readCapacityUnits", td.getProvisionedThroughput().getReadCapacityUnits());
					put("writeCapacityUnits", td.getProvisionedThroughput().getWriteCapacityUnits());
				}
			};
		} catch (Exception e) {
			logger.error(null, e);
		}
		return Collections.emptyMap();
	}

	/**
	 * Lists all table names for this account.
	 * @return a list of DynamoDB tables
	 */
	public static List<String> listAllTables() {
		int items = 100;
		ListTablesResult ltr = getClient().listTables(items);
		List<String> tables = new LinkedList<String>();
		String lastKey;
		do {
			tables.addAll(ltr.getTableNames());
			lastKey = ltr.getLastEvaluatedTableName();
			logger.info("Found {} tables. Total found: {}.", ltr.getTableNames().size(), tables.size());
			if (lastKey == null) {
				break;
			}
			ltr = getClient().listTables(lastKey, items);
		} while (!ltr.getTableNames().isEmpty());
		return tables;
	}

	/**
	 * Returns the table name for a given app id. Table names are usually in the form 'prefix-appid'.
	 * @param appIdentifier app id
	 * @return the table name
	 */
	public static String getTableNameForAppid(String appIdentifier) {
		if (StringUtils.isBlank(appIdentifier)) {
			return null;
		} else {
			if (isSharedAppid(appIdentifier)) {
				// app is sharing a table with other apps
				appIdentifier = SHARED_TABLE;
			}
			return (appIdentifier.equals(Config.APP_NAME_NS) || appIdentifier.startsWith(Config.PARA.concat("-"))) ?
					appIdentifier : Config.PARA + "-" + appIdentifier;
		}
	}

	/**
	 * Returns the correct key for an object given the appid (table name).
	 * @param key a row id
	 * @param appIdentifier appid
	 * @return the key
	 */
	public static String getKeyForAppid(String key, String appIdentifier) {
		if (StringUtils.isBlank(key) || StringUtils.isBlank(appIdentifier)) {
			return key;
		}
		if (isSharedAppid(appIdentifier)) {
			// app is sharing a table with other apps, key is composite "appid_key"
			return keyPrefix(appIdentifier) + key;
		} else {
			return key;
		}
	}

	/**
	 * Returns the Index object for the shared table.
	 * @return the Index object or null
	 */
	public static Index getSharedIndex() {
		if (ddb == null) {
			getClient();
		}
		try {
			Table t = ddb.getTable(getTableNameForAppid(SHARED_TABLE));
			if (t != null) {
				return t.getIndex(getSharedIndexName());
			}
		} catch (Exception e) {
			logger.info("Could not get shared index: {}.", e.getMessage());
		}
		return null;
	}

	/**
	 * Returns true if appid starts with a space " ".
	 * @param appIdentifier appid
	 * @return true if appid starts with " "
	 */
	public static boolean isSharedAppid(String appIdentifier) {
		return StringUtils.startsWith(appIdentifier, " ");
	}

	private static String getSharedIndexName() {
		return "Index_" + SHARED_TABLE;
	}

	private static String keyPrefix(String appIdentifier) {
		return StringUtils.join(StringUtils.trim(appIdentifier), "_");
	}

}
