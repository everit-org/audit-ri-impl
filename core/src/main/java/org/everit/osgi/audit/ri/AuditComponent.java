/**
 * This file is part of org.everit.osgi.audit.ri.
 *
 * org.everit.osgi.audit.ri is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * org.everit.osgi.audit.ri is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with org.everit.osgi.audit.ri.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.everit.osgi.audit.ri;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.sql.DataSource;
import javax.sql.rowset.serial.SerialBlob;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.everit.osgi.audit.api.AuditService;
import org.everit.osgi.audit.api.dto.Application;
import org.everit.osgi.audit.api.dto.Event;
import org.everit.osgi.audit.api.dto.EventData;
import org.everit.osgi.audit.api.dto.EventType;
import org.everit.osgi.audit.api.dto.EventUi;
import org.everit.osgi.audit.api.dto.FieldWithType;
import org.everit.osgi.audit.ri.schema.qdsl.QApplication;
import org.everit.osgi.audit.ri.schema.qdsl.QEvent;
import org.everit.osgi.audit.ri.schema.qdsl.QEventData;
import org.everit.osgi.audit.ri.schema.qdsl.QEventType;
import org.everit.osgi.resource.api.ResourceService;
import org.everit.osgi.transaction.helper.api.Callback;
import org.everit.osgi.transaction.helper.api.TransactionHelper;

import com.mysema.query.sql.SQLQuery;
import com.mysema.query.sql.SQLTemplates;
import com.mysema.query.sql.dml.SQLInsertClause;
import com.mysema.query.types.ConstructorExpression;

@Component(name = "AuditComponent",
        immediate = true,
        metatype = true,
        configurationFactory = true,
        policy = ConfigurationPolicy.REQUIRE)
@Properties({
        @Property(name = "sqlTemplates.target"),
        @Property(name = "dataSource.target"),
        @Property(name = "resourceService.target")
})
@Service
public class AuditComponent implements AuditService {

    private final class EventPersister implements Callback<Void> {

        private final Event event;

        private long resourceId;

        private EventType evtType;

        private Connection conn;

        private EventPersister(final Event event) {
            this.event = event;
        }

        private void addEventDataRowToBatchInsert(final long eventId, final SQLInsertClause insert,
                final EventData eventData) {
            QEventData evtData = QEventData.auditEventData;
            insert.set(evtData.eventId, eventId);
            insert.set(evtData.eventDataName, eventData.getName());
            insert.set(evtData.eventDataType, eventData.getEventDataType().toString());
            addEventDataValue(insert, eventData);
            insert.addBatch();
        }

        private void addEventDataValue(final SQLInsertClause insert, final EventData eventData) {
            QEventData evtData = QEventData.auditEventData;
            try {
                switch (eventData.getEventDataType()) {
                case NUMBER:
                    insert.set(evtData.numberValue, eventData.getNumberValue());
                    break;
                case STRING:
                    insert.set(evtData.stringValue, eventData.getTextValue());
                    break;
                case TEXT:
                    insert.set(evtData.textValue, eventData.getTextValue());
                    break;
                case BINARY:
                    insert.set(evtData.binaryValue, new SerialBlob(eventData.getBinaryValue()));
                    break;
                case TIMESTAMP:
                    insert.set(evtData.timestampValue, new Timestamp(eventData.getTimestampValue().getTimeInMillis()));
                    break;
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Void execute() {
            resourceId = resourceService.createResource();
            evtType = getOrCreateEventType(event.getApplicationName(), event.getName());
            try {
                try {
                    conn = dataSource.getConnection();
                    QEvent evt = QEvent.auditEvent;
                    long eventId = insertEventRow(evt);
                    QEventData evtData = QEventData.auditEventData;
                    SQLInsertClause insert = new SQLInsertClause(conn, sqlTemplates, evtData);
                    for (EventData eventData : event.getEventDataArray()) {
                        addEventDataRowToBatchInsert(eventId, insert, eventData);
                    }
                    insert.execute();
                } finally {
                    conn.close();
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return null;
        }

        private long insertEventRow(final QEvent evt) {
            return new SQLInsertClause(conn, sqlTemplates, evt)
                    .set(evt.resourceId, resourceId)
                    .set(evt.saveTimestamp, new Timestamp(event.getSaveTimeStamp().getTime()))
                    .set(evt.eventTypeId, evtType.getId())
                    .executeWithKey(evt.eventId).longValue();
        }
    }

    @Reference
    private SQLTemplates sqlTemplates;

    @Reference
    private DataSource dataSource;

    @Reference
    private ResourceService resourceService;

    @Reference
    private TransactionHelper transactionHelper;

    public void bindDataSource(final DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource cannot be null");
    }

    public void bindResourceService(final ResourceService resourceService) {
        this.resourceService = Objects.requireNonNull(resourceService, "resourceService cannot be null");
    }

    public void bindSqlTemplates(final SQLTemplates sqlTemplates) {
        this.sqlTemplates = Objects.requireNonNull(sqlTemplates, "sqlTemplates cannot be null");
    }

    public void bindTransactionHelper(final TransactionHelper transactionHelper) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "transactionHelper cannot be null");
    }

    @Override
    public Application createApplication(final String appName) {
        return createApplication(appName, null);
    }

    @Override
    public Application createApplication(final String appName, final Long resourceId) {
        Objects.requireNonNull(appName, "appName cannot be null");
        return transactionHelper.required(new Callback<Application>() {

            @Override
            public Application execute() {
                try (Connection conn = dataSource.getConnection()) {
                    Long insertedResourceId;
                    if (resourceId == null) {
                        insertedResourceId = resourceService.createResource();
                    } else {
                        insertedResourceId = resourceId;
                    }
                    QApplication app = QApplication.auditApplication;
                    Long appId = new SQLInsertClause(conn, sqlTemplates, app)
                            .set(app.resourceId, insertedResourceId)
                            .set(app.applicationName, appName)
                            .executeWithKey(app.applicationId);
                    return new Application(appId, appName, insertedResourceId);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    private EventType createEventType(final Application app, final String eventTypeName) {
        try (Connection conn = dataSource.getConnection()) {
            Long resourceId = resourceService.createResource();
            QEventType evtType = QEventType.auditEventType;
            Long eventTypeId = new SQLInsertClause(conn, sqlTemplates, evtType)
            .set(evtType.name, eventTypeName)
            .set(evtType.applicationId, app.getApplicationId())
            .set(evtType.resourceId, resourceId)
            .executeWithKey(evtType.eventTypeId);
            return new EventType(eventTypeId, eventTypeName, app.getApplicationId());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Application findAppByName(final String appName) {
        Objects.requireNonNull(appName, "appName cannot be null");
        try (Connection conn = dataSource.getConnection()) {
            QApplication app = QApplication.auditApplication;
            return new SQLQuery(conn, sqlTemplates)
                    .from(app)
                    .where(app.applicationName.eq(appName))
                    .uniqueResult(ConstructorExpression.create(Application.class,
                            app.applicationId,
                            app.applicationName,
                            app.resourceId));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Application findApplicationByName(final String applicationName) {
        try (Connection conn = dataSource.getConnection()) {
            QApplication app = QApplication.auditApplication;
            return new SQLQuery(conn, sqlTemplates)
            .from(app)
            .where(app.applicationName.eq(applicationName))
            .uniqueResult(ConstructorExpression.create(Application.class, app.applicationId,
                            app.applicationName,
                            app.resourceId));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private EventType findEventType(final long applicationId, final String eventTypeName) {
        try (Connection conn = dataSource.getConnection()) {
            QEventType evtType = QEventType.auditEventType;
            return new SQLQuery(conn, sqlTemplates)
            .from(evtType)
            .where(evtType.name.eq(eventTypeName).and(evtType.applicationId.eq(applicationId)))
            .uniqueResult(ConstructorExpression.create(EventType.class,
                    evtType.eventTypeId,
                    evtType.name,
                    evtType.applicationId));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<Application> getApplications() {
        try (Connection conn = dataSource.getConnection()) {
            QApplication app = QApplication.auditApplication;
            return new SQLQuery(conn, sqlTemplates)
                    .from(app)
                    .listResults(ConstructorExpression.create(Application.class,
                            app.applicationId,
                            app.applicationName,
                            app.resourceId)).getResults();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public EventUi getEventById(final long eventId) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public EventType getEventTypeByNameForApplication(final long selectedAppId, final String eventName) {
        Objects.requireNonNull(eventName, "eventName cannot be null");
        try (Connection conn = dataSource.getConnection()) {
            QEventType evtType = QEventType.auditEventType;
            EventType rval = new SQLQuery(conn, sqlTemplates)
            .from(evtType)
            .where(evtType.applicationId.eq(selectedAppId))
            .where(evtType.name.eq(eventName))
            .uniqueResult(ConstructorExpression.create(EventType.class,
                    evtType.eventTypeId,
                    evtType.name,
                    evtType.applicationId));
            if (rval == null) {
                throw new IllegalArgumentException("not event type found for application #" + selectedAppId
                        + " with name [" + eventName + "]");
            }
            return rval;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<EventType> getEventTypesByApplication(final long selectedAppId) {
        try (Connection conn = dataSource.getConnection()) {
            QEventType evtType = QEventType.auditEventType;
            return new SQLQuery(conn, sqlTemplates)
            .from(evtType)
            .where(evtType.applicationId.eq(selectedAppId))
            .list(ConstructorExpression.create(EventType.class,
                    evtType.eventTypeId,
                    evtType.name,
                    evtType.applicationId));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Application getOrCreateApplication(final String applicationName) {
        Application rval;
        return (rval = findAppByName(applicationName)) != null ? rval : createApplication(applicationName);
    }

    @Override
    public EventType getOrCreateEventType(final String applicationName, final String eventTypeName) {
        Objects.requireNonNull(applicationName, "applicationName cannot be null");
        Objects.requireNonNull(eventTypeName, "eventTypeName cannot be null");
        return transactionHelper.required(new Callback<EventType>() {

            @Override
            public EventType execute() {
                Application app = findApplicationByName(applicationName);
                if (app == null) {
                    throw new IllegalArgumentException("application [" + applicationName + "] does not exist");
                }
                EventType existing = findEventType(app.getApplicationId(), eventTypeName);
                if (existing != null) {
                    return existing;
                }
                return createEventType(app, eventTypeName);
            }

        });
    }

    @Override
    public EventType[] getOrCreateEventTypes(final String applicationName, final String[] eventTypeNames) {
        Objects.requireNonNull(applicationName, "applicationName cannot be null");
        Objects.requireNonNull(eventTypeNames, "eventTypeNames cannot be null");
        return transactionHelper.required(new Callback<EventType[]>() {

            @Override
            public EventType[] execute() {
                Application app = findAppByName(applicationName);
                if (app == null) {
                    throw new IllegalArgumentException("application [" + applicationName + "] does not exist");
                }
                EventType[] rval = new EventType[eventTypeNames.length];
                int idx = 0;
                for (String typeName : eventTypeNames) {
                    rval[idx++] = getOrCreateEventType(applicationName, typeName);
                }
                return rval;
            }
        });
    }

    @Override
    public List<FieldWithType> getResultFieldsWithTypes(final Long[] selectedAppId, final Long[] selectedEventTypeId) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void logEvent(final Event event) {
        transactionHelper.required(new EventPersister(event));
    }

    @Override
    public void logEvent(final String eventName, final String appName,
            final List<Map<String, Serializable>> eventDataList) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public EventUi readEvent(final long eventId, final List<String> dataFields) {
        // TODO Auto-generated method stub
        return null;
    }
}
