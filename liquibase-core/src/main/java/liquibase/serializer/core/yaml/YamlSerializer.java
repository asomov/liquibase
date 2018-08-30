package liquibase.serializer.core.yaml;

import liquibase.change.ConstraintsConfig;
import liquibase.changelog.ChangeSet;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.serializer.LiquibaseSerializable;
import liquibase.serializer.LiquibaseSerializer;
import liquibase.statement.DatabaseFunction;
import liquibase.statement.SequenceCurrentValueFunction;
import liquibase.statement.SequenceNextValueFunction;
import liquibase.structure.core.Column;
import liquibase.structure.core.DataType;

import java.beans.IntrospectionException;
import java.lang.reflect.Type;
import java.util.*;

import org.snakeyaml.engine.api.Dump;
import org.snakeyaml.engine.api.DumpSettings;
import org.snakeyaml.engine.api.RepresentToNode;
import org.snakeyaml.engine.common.FlowStyle;
import org.snakeyaml.engine.common.ScalarStyle;
import org.snakeyaml.engine.nodes.Node;
import org.snakeyaml.engine.nodes.Tag;
import org.snakeyaml.engine.representer.StandardRepresenter;

public abstract class YamlSerializer implements LiquibaseSerializer {

    protected Dump yaml;

    public YamlSerializer() {
        yaml = createYaml();
    }

    protected Dump createYaml() {
        DumpSettings dumpSettings = new DumpSettings();

        if (isJson()) {
            dumpSettings.setPrettyFlow(true);
            dumpSettings.setDefaultFlowStyle(FlowStyle.FLOW);
            dumpSettings.setDefaultScalarStyle(ScalarStyle.DOUBLE_QUOTED);
            dumpSettings.setWidth(Integer.MAX_VALUE);
        } else {
            dumpSettings.setDefaultFlowStyle(FlowStyle.BLOCK);
        }
        return new Dump(dumpSettings, getLiquibaseRepresenter(dumpSettings));
    }

    protected LiquibaseRepresenter getLiquibaseRepresenter(DumpSettings dumpSettings) {
        return new LiquibaseRepresenter(dumpSettings);
    }

    protected boolean isJson() {
        return "json".equals(getValidFileExtensions()[0]);
    }

    @Override
    public String[] getValidFileExtensions() {
        return new String[]{
                "yaml",
                "yml"
        };
    }

    @Override
    public String serialize(LiquibaseSerializable object, boolean pretty) {
        if (isJson()) {
            String out = yaml.dumpToString(toMap(object));
            return removeClassTypeMarksFromSerializedJson(out);
        } else {
            return yaml.dumpToString(toMap(object));
        }
    }

    protected Object toMap(LiquibaseSerializable object) {
        Comparator<String> comparator;
        comparator = getComparator(object);
        Map<String, Object> objectMap = new TreeMap<>(comparator);

        for (String field : object.getSerializableFields()) {
            Object value = object.getSerializableFieldValue(field);
            if (value != null) {
                if (value instanceof DataType) {
                    value = ((Map) toMap((DataType) value)).values().iterator().next();
                }
                if (value instanceof Column.AutoIncrementInformation) {
                    value = ((Map) toMap((Column.AutoIncrementInformation) value)).values().iterator().next();
                }
                if (value instanceof ConstraintsConfig) {
                    value = ((Map) toMap((ConstraintsConfig) value)).values().iterator().next();
                }
                if (value instanceof LiquibaseSerializable) {
                    value = toMap((LiquibaseSerializable) value);
                }
                if (value instanceof Collection) {
                    List valueAsList = new ArrayList((Collection) value);
                    if (valueAsList.isEmpty()) {
                        continue;
                    }
                    for (int i = 0; i < valueAsList.size(); i++) {
                        if (valueAsList.get(i) instanceof LiquibaseSerializable) {
                            valueAsList.set(i, toMap((LiquibaseSerializable) valueAsList.get(i)));
                        }
                    }
                    value = valueAsList;

                }
                if (value instanceof Map) {
                    if  (((Map) value).isEmpty()) {
                        continue;
                    }

                    for (Object key : ((Map) value).keySet()) {
                        Object mapValue = ((Map) value).get(key);
                        if (mapValue instanceof LiquibaseSerializable) {
                            ((Map) value).put(key, toMap((LiquibaseSerializable) mapValue));
                        } else if (mapValue instanceof Collection) {
                            List valueAsList = new ArrayList((Collection) mapValue);
                            if (valueAsList.isEmpty()) {
                                continue;
                            }
                            for (int i = 0; i < valueAsList.size(); i++) {
                                if (valueAsList.get(i) instanceof LiquibaseSerializable) {
                                    valueAsList.set(i, toMap((LiquibaseSerializable) valueAsList.get(i)));
                                }
                            }
                            ((Map) value).put(key, valueAsList);
                        }
                    }


                }
                objectMap.put(field, value);
            }
        }

        Map<String, Object> containerMap = new HashMap<>();
        containerMap.put(object.getSerializedObjectName(), objectMap);
        return containerMap;
    }

    protected Comparator<String> getComparator(LiquibaseSerializable object) {
        return new Comparator<String>() {
            @Override
            public int compare(String o1, String o2) {
                return o1.compareTo(o2);
            }
        };
    }

    private String removeClassTypeMarksFromSerializedJson(String json) {
        json = json.replaceAll("!!int \"(\\d+)\"", "$1");
        json = json.replaceAll("!!bool \"(\\w+)\"", "$1");
        json = json.replaceAll("!!timestamp \"([^\"]*)\"", "$1");
        json = json.replaceAll("!!float \"([^\"]*)\"", "$1");
        json = json.replaceAll("!!liquibase.[^\\s]+ (\"\\w+\")", "$1");
        if (json.contains("!!")) {
            throw new IllegalStateException(String.format("Serialize failed. Illegal char on %s position: %s", json.indexOf("!!"), json));
        }
        return json;
    }

    public static class LiquibaseRepresenter extends StandardRepresenter {

        public LiquibaseRepresenter(DumpSettings dumpSettings) {
            super(dumpSettings);
            init();
        }

        protected void init() {
            multiRepresenters.put(DatabaseFunction.class, new AsStringRepresenter());
            multiRepresenters.put(SequenceNextValueFunction.class, new AsStringRepresenter());
            multiRepresenters.put(SequenceCurrentValueFunction.class, new AsStringRepresenter());
        }

        /*
        @Override
        protected Set<Property> getProperties(Class<? extends Object> type) throws IntrospectionException {
            Set<Property> returnSet = new HashSet<>();
            LiquibaseSerializable serialzableType = null;
            try {
                if (type.equals(ChangeSet.class)) {
                    serialzableType = new ChangeSet("x", "y", false, false, null, null, null, null);
                } else if (LiquibaseSerializable.class.isAssignableFrom(type)) {
                    serialzableType = (LiquibaseSerializable) type.newInstance();
                } else {
                    return super.getProperties(type);
                }
            } catch (InstantiationException | IllegalAccessException e) {
                throw new UnexpectedLiquibaseException(e);
            }
            for (String property : serialzableType.getSerializableFields()) {
                returnSet.add(new LiquibaseProperty(property, String.class, String.class));
            }
            return returnSet;
        }

        private static class LiquibaseProperty extends GenericProperty {

            private LiquibaseProperty(String name, Class<?> aClass, Type aType) {
                super(name, aClass, aType);
            }

            @Override
            public void set(Object object, Object value) throws Exception {
                //not supported
            }

            @Override
            public Object get(Object object) {
                return ((LiquibaseSerializable) object).getSerializableFieldValue(getName());
            }
        }
        */

        private class AsStringRepresenter implements RepresentToNode {
            @Override
            public Node representData(Object data) {
                return representScalar(Tag.STR, data.toString());
            }
        }
    }
}
