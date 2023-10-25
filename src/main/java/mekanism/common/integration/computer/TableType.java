package mekanism.common.integration.computer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import mekanism.common.util.MekCodecs;

/**
 * Defines the format of a "table" (Map) format of a Mekanism data structure
 */
public record TableType(String description, String humanName, Map<String, FieldType> fields, Class<?> extendedFrom) {

    public static Codec<TableType> CODEC = RecordCodecBuilder.create(instance ->
          instance.group(
                Codec.STRING.fieldOf("description").forGetter(TableType::description),
                Codec.STRING.fieldOf("humanName").forGetter(TableType::humanName),
                Codec.unboundedMap(Codec.STRING, FieldType.CODEC).optionalFieldOf("fields", Collections.emptyMap()).forGetter(TableType::fields),
                MekCodecs.CLASS_TO_STRING_CODEC.optionalFieldOf("extends", null).forGetter(TableType::extendedFrom)
          ).apply(instance, TableType::new)
    );
    public static Codec<Map<Class<?>, TableType>> TABLE_MAP_CODEC = Codec.unboundedMap(MekCodecs.CLASS_TO_STRING_CODEC, CODEC);

    public static Builder builder(Class<?> clazz, String description) {
        return new Builder(clazz, description);
    }

    public record FieldType(String description, Class<?> javaType, String type, Class<?>[] javaExtra){
        public static final Codec<FieldType> CODEC = RecordCodecBuilder.create(instance ->
              instance.group(
                    Codec.STRING.fieldOf("description").forGetter(FieldType::description),
                    MekCodecs.CLASS_TO_STRING_CODEC.fieldOf("javaType").forGetter(FieldType::javaType),
                    Codec.STRING.fieldOf("type").forGetter(FieldType::type),
                    MekCodecs.optionalClassArrayCodec("javaExtra").forGetter(FieldType::javaExtra)
              ).apply(instance, FieldType::new)
        );
    }

    public static class Builder {

        private final Class<?> clazz;
        private final String description;
        private final String humanName;
        private final Map<String, FieldType> fields = new LinkedHashMap<>();
        private Class<?> extendedFrom = null;

        private Builder(Class<?> clazz, String description) {
            this.clazz = clazz;
            this.description = description;
            this.humanName = MethodHelpData.getHumanType(clazz, ComputerMethodFactory.NO_CLASSES);
        }

        public Builder extendedFrom(Class<?> c) {
            this.extendedFrom = c;
            return this;
        }

        public Builder addField(String name, Class<?> javaType, String description, Class<?>... javaExtra) {
            if (javaExtra == null) {
                javaExtra = ComputerMethodFactory.NO_CLASSES;
            }
            this.fields.put(name, new FieldType(description, javaType, MethodHelpData.getHumanType(javaType, javaExtra), javaExtra));
            return this;
        }

        public TableType build(Map<Class<?>, TableType> destination) {
            TableType tableType = new TableType(description, humanName, new LinkedHashMap<>(fields), extendedFrom);
            destination.put(clazz, tableType);
            return tableType;
        }
    }
}