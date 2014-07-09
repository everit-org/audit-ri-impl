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

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import org.everit.osgi.audit.api.dto.DataFilter;
import org.everit.osgi.audit.api.dto.Event;
import org.everit.osgi.audit.api.dto.EventData;
import org.everit.osgi.audit.api.dto.EventDataType;
import org.everit.osgi.audit.api.dto.EventType;
import org.everit.osgi.audit.api.dto.EventUi;
import org.everit.osgi.audit.api.dto.FieldWithType;
import org.everit.osgi.audit.ri.schema.qdsl.QApplication;
import org.everit.osgi.audit.ri.schema.qdsl.QEvent;
import org.everit.osgi.audit.ri.schema.qdsl.QEventData;
import org.everit.osgi.audit.ri.schema.qdsl.QEventType;
import org.everit.osgi.resource.api.ResourceService;
import org.everit.osgi.transaction.helper.api.TransactionHelper;

import com.mysema.query.Tuple;
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
        @Property(name = "resourceService.target"),
        @Property(name = "localizationService.target")
})
@Service
public class AuditComponent implements AuditService {

    private final class EventPersister implements Supplier<Void> {

        private final Event event;

        private long resourceId;

        private EventType evtType;

        private Connection conn;

        private EventPersister(final Event event) {
            this.event = event;
        }

        private void addEventDataValue(final SQLInsertClause insert, final EventData eventData) {
            QEventData evtData = new QEventData("evtData");
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
                    insert.set(evtData.timestampValue, instantToTimestamp(eventData.getTimestampValue()));
                    break;
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Void get() {
            resourceId = resourceService.createResource();
            evtType = getOrCreateEventType(event.getApplicationName(), event.getName());
            try {
                try {
                    conn = dataSource.getConnection();
                    long eventId = insertEventRow();
                    Stream.of(event.getEventDataArray()).forEach((eventData) -> insertEventDataRow(eventId, eventData));
                } finally {
                    conn.close();
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return null;
        }

        private void insertEventDataRow(final long eventId, final EventData eventData) {
            QEventData evtData = new QEventData("evtData");
            SQLInsertClause insert = new SQLInsertClause(conn, sqlTemplates, evtData);
            insert.set(evtData.eventId, eventId)
            .set(evtData.eventDataName, eventData.getName())
            .set(evtData.eventDataType, eventData.getEventDataType().toString());
            addEventDataValue(insert, eventData);
            insert.execute();
        }

        private long insertEventRow() {
            return new SQLInsertClause(conn, sqlTemplates, evt)
                    .set(evt.resourceId, resourceId)
                    .set(evt.saveTimestamp, instantToTimestamp(event.getSaveTimeStamp()))
                    .set(evt.eventTypeId, evtType.getId())
                    .executeWithKey(evt.eventId).longValue();
        }
    }

    static final Timestamp instantToTimestamp(final Instant inst) {
        Timestamp rval = new Timestamp(inst.getEpochSecond() * 1000);
        rval.setNanos(inst.getNano());
        return rval;
    }

    static final Instant timestampToInstant(final Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getTime() / 1000, timestamp.getNanos());
    }

    private static final QApplication app = new QApplication("app");

    private static final QEventType evtType = new QEventType("evtType");

    private static final QEvent evt = new QEvent("evt");

    private static final QEventData evtData = new QEventData("evtData");

    @Reference(bind = "setSqlTemplates")
    private SQLTemplates sqlTemplates;

    @Reference(bind = "setDataSource")
    private DataSource dataSource;

    @Reference(bind = "setResourceService")
    private ResourceService resourceService;

    @Reference(bind = "setTransactionHelper")
    private TransactionHelper transactionHelper;

    // @Reference
    // private LocalizationService localizationService;

    @Override
    public Application createApplication(final String appName) {
        return createApplication(appName, null);
    }

    // public void bindLocalizationService(final LocalizationService localizationService) {
    // this.localizationService = Objects.requireNonNull(localizationService, "localizationService cannot be null");
    // }

    @Override
    public Application createApplication(final String appName, final Long resourceId) {
        Objects.requireNonNull(appName, "appName cannot be null");
        return transactionHelper.required(() -> {
            try (Connection conn = dataSource.getConnection()) {
                Long insertedResourceId = Optional.ofNullable(resourceId).orElseGet(resourceService::createResource);
                Long appId = new SQLInsertClause(conn, sqlTemplates, app)
                        .set(app.resourceId, insertedResourceId)
                        .set(app.applicationName, appName)
                        .executeWithKey(app.applicationId);
                return new Application(appId, appName, insertedResourceId);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private EventType createEventType(final Application app, final String eventTypeName) {
        try (Connection conn = dataSource.getConnection()) {
            Long resourceId = resourceService.createResource();
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

    @Override
    public List<EventUi> findEvents(final Long[] selectedAppIds, final Long[] selectedEventTypeIds,
            final List<String> dataFields,
            final List<DataFilter> dataFilters, final Instant eventsFrom, final Instant eventsTo,
            final Locale locale, final long offset, final long limit) {
        try (Connection conn = dataSource.getConnection()) {
            return new ComplexEventLoader(conn, sqlTemplates,
                    // localizationService,
                    selectedAppIds,
                    selectedEventTypeIds, dataFields,
                    dataFilters, eventsFrom, eventsTo, locale, offset, limit).loadEvents();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

    }

    private EventType findEventType(final long applicationId, final String eventTypeName) {
        try (Connection conn = dataSource.getConnection()) {
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
        try (Connection conn = dataSource.getConnection()) {
            return new SingleEventLoader(conn, sqlTemplates).loadEvent(eventId);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public EventType getEventTypeByNameForApplication(final long selectedAppId, final String eventName) {
        Objects.requireNonNull(eventName, "eventName cannot be null");
        try (Connection conn = dataSource.getConnection()) {
            EventType rval = new SQLQuery(conn, sqlTemplates)
            .from(evtType)
            .where(evtType.applicationId.eq(selectedAppId))
            .where(evtType.name.eq(eventName))
            .uniqueResult(ConstructorExpression.create(EventType.class,
                    evtType.eventTypeId,
                    evtType.name,
                    evtType.applicationId));
            return Optional.ofNullable(rval)
                    .orElseThrow(() -> new IllegalArgumentException("not event type found for application #"
                            + selectedAppId + " with name [" + eventName + "]"));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<EventType> getEventTypesByApplication(final long selectedAppId) {
        try (Connection conn = dataSource.getConnection()) {
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
        return Optional.ofNullable(findAppByName(applicationName)).orElseGet(() -> createApplication(applicationName));
    }

    @Override
    public EventType getOrCreateEventType(final String applicationName, final String eventTypeName) {
        Objects.requireNonNull(applicationName, "applicationName cannot be null");
        Objects.requireNonNull(eventTypeName, "eventTypeName cannot be null");
        return transactionHelper
                .required(() -> {
                    Application app = requireAppByName(applicationName);
                    return Optional.ofNullable(findEventType(app.getApplicationId(), eventTypeName))
                            .orElseGet(() -> createEventType(app, eventTypeName));
                });
    }

    @Override
    public EventType[] getOrCreateEventTypes(final String applicationName, final String[] eventTypeNames) {
        Objects.requireNonNull(applicationName, "applicationName cannot be null");
        Objects.requireNonNull(eventTypeNames, "eventTypeNames cannot be null");
        return transactionHelper.required(() -> {
            requireAppByName(applicationName);
            EventType[] rval = new EventType[eventTypeNames.length];
            int idx = 0;
            for (String typeName : eventTypeNames) {
                rval[idx++] = getOrCreateEventType(applicationName, typeName);
            }
            return rval;
        });
    }

    @Override
    public List<FieldWithType> getResultFieldsWithTypes(final Long[] selectedAppId, final Long[] selectedEventTypeId) {
        try (Connection conn = dataSource.getConnection()) {
            List<Tuple> rawResult = new SQLQuery(conn, sqlTemplates)
            .from(evt)
            .join(evtData)
            .on(evt.eventId.eq(evtData.eventId))
            .join(evtType)
            .on(evt.eventTypeId.eq(evtType.eventTypeId))
            .where(evtType.applicationId.in(Arrays.asList(selectedAppId))
                    .and(evtType.eventTypeId.in(Arrays.asList(selectedEventTypeId))))
                    .distinct()
                    .list(evtData.eventDataName, evtData.eventDataType);
            return rawResult.stream().map((row) ->
            new FieldWithType(row.get(evtData.eventDataName),
                    EventDataType.valueOf(row.get(evtData.eventDataType)),
                    null)).collect(Collectors.toList());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void logEvent(final Event event) {
        transactionHelper.required(new EventPersister(event));
    }

    @Override
    public EventUi readEvent(final long eventId, final Set<String> dataFields) {
        try (Connection conn = dataSource.getConnection()) {
            return new SingleEventLoader(conn, sqlTemplates).loadEvent(eventId,
                    evtData.eventDataName.in(dataFields));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Application requireAppByName(final String applicationName) {
        Application app = Optional
                .ofNullable(findApplicationByName(applicationName))
                .orElseThrow(() -> new IllegalArgumentException("application [" + applicationName + "] does not exist"));
        return app;
    }

    public void setDataSource(final DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource cannot be null");
    }

    public void setResourceService(final ResourceService resourceService) {
        this.resourceService = Objects.requireNonNull(resourceService, "resourceService cannot be null");
    }

    public void setSqlTemplates(final SQLTemplates sqlTemplates) {
        this.sqlTemplates = Objects.requireNonNull(sqlTemplates, "sqlTemplates cannot be null");
    }

    public void setTransactionHelper(final TransactionHelper transactionHelper) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "transactionHelper cannot be null");
    }

}
