package org.cloudburstmc.proxypass.network.bedrock.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.*;
import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.util.stream.LittleEndianDataOutputStream;
import org.cloudburstmc.protocol.bedrock.BedrockSession;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.biome.BiomeDefinitionData;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemGroup;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.bedrock.definition.DefinitionRegistry;
import org.cloudburstmc.protocol.bedrock.packet.PacketSignal;
import org.cloudburstmc.protocol.bedrock.definition.SimpleDefinitionRegistry;
import org.cloudburstmc.proxypass.ProxyPass;
import org.cloudburstmc.proxypass.network.bedrock.util.ItemDefinitionRegistries;
import org.cloudburstmc.proxypass.network.bedrock.util.NbtBlockDefinitionRegistry;
import org.cloudburstmc.proxypass.network.bedrock.util.RecipeUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Log4j2
@RequiredArgsConstructor
@SuppressWarnings("deprecation")
public class DownstreamPacketHandler implements BedrockPacketHandler {
    private final BedrockSession session;
    private final ProxyPlayerSession player;
    private final ProxyPass proxy;

    private final List<NbtMap> entityProperties = new ArrayList<>();

    @Override
    public PacketSignal handle(AvailableEntityIdentifiersPacket packet) {
        proxy.saveNBT("entity_identifiers", packet.getIdentifiers());
        return PacketSignal.UNHANDLED;
    }

    // Legacy - Versions prior to 1.21.80 (800) when client-side chunk generation is enabled
    @Override
    public PacketSignal handle(CompressedBiomeDefinitionListPacket packet) {
        proxy.saveNBT("biome_definitions_full", packet.getDefinitions());
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(BiomeDefinitionListPacket packet) {
        if (packet.getDefinitions() != null) {
            // Legacy - Versions prior to 1.21.80 (800) when client-side chunk generation is disabled
            proxy.saveNBT("biome_definitions", packet.getDefinitions());
        }

        if (packet.getBiomes() != null) {
            Map<String, BiomeDefinitionData> definitions = packet.getBiomes().definitions();
            Map<String, BiomeDefinitionData> strippedDefinitions = new LinkedHashMap<>();

            // Enable client-side chunk generation
            proxy.saveJson("biome_definitions.json", packet.getBiomes().definitions());

            for (Map.Entry<String, BiomeDefinitionData> entry : definitions.entrySet()) {
                String id = entry.getKey();
                BiomeDefinitionData data = entry.getValue();

                strippedDefinitions.put(id, new BiomeDefinitionData(data.id(), data.temperature(), data.downfall(), data.redSporeDensity(),
                        data.blueSporeDensity(), data.ashDensity(), data.whiteAshDensity(), data.foliageSnow(),
                        data.depth(), data.scale(), data.mapWaterColor(), data.rain(), data.tags(), null));
            }

            proxy.saveJson("stripped_biome_definitions.json", strippedDefinitions);
        }

        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(StartGamePacket packet) {
        if (ProxyPass.CODEC.getProtocolVersion() < 776) {
            List<DataEntry> itemData = new ArrayList<>();

            LinkedHashMap<String, Integer> legacyItems = new LinkedHashMap<>();
            LinkedHashMap<String, Integer> legacyBlocks = new LinkedHashMap<>();

            for (ItemDefinition entry : packet.getItemDefinitions()) {
                if (entry.runtimeId() > 255) {
                    legacyItems.putIfAbsent(entry.identifier(), entry.runtimeId());
                } else {
                    String id = entry.identifier();
                    if (id.contains(":item.")) {
                        id = id.replace(":item.", ":");
                    }
                    if (entry.runtimeId() > 0) {
                        legacyBlocks.putIfAbsent(id, entry.runtimeId());
                    } else {
                        legacyBlocks.putIfAbsent(id, 255 - entry.runtimeId());
                    }
                }

                itemData.add(new DataEntry(entry.identifier(), entry.runtimeId(), -1, false));
                ProxyPass.legacyIdMap.put(entry.runtimeId(), entry.identifier());
            }

            SimpleDefinitionRegistry<ItemDefinition> itemDefinitions = SimpleDefinitionRegistry.<ItemDefinition>builder()
                    .addAll(packet.getItemDefinitions())
                    .add(new SimpleItemDefinition("minecraft:empty", 0, false))
                    .build();

            this.session.getPeer().getCodecHelper().setItemDefinitions(itemDefinitions);
            if (player != null) {
                player.getUpstream().getPeer().getCodecHelper().setItemDefinitions(itemDefinitions);
            }

            itemData.sort(Comparator.comparing(o -> o.name));

            proxy.saveJson("legacy_block_ids.json", sortMap(legacyBlocks));
            proxy.saveJson("legacy_item_ids.json", sortMap(legacyItems));
            proxy.saveJson("runtime_item_states.json", itemData);
        }


        DefinitionRegistry<BlockDefinition> registry;
        if (packet.isBlockNetworkIdsHashed()) {
            registry = this.proxy.getBlockDefinitionsHashed();
        } else {
            registry = this.proxy.getBlockDefinitions();
        }

        this.session.getPeer().getCodecHelper().setBlockDefinitions(registry);
        if (player != null) {
            player.getUpstream().getPeer().getCodecHelper().setBlockDefinitions(registry);
        }

        NbtMapBuilder blockProperties = NbtMap.builder();
        for (BlockPropertyData property : packet.getBlockProperties()) {
            blockProperties.putCompound(property.name(), property.properties());
        }
        proxy.saveCompressedNBT("data_driven_blocks", blockProperties.build());

        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(VoxelShapesPacket packet) {
        Map<String, Object> voxelShapes = new LinkedHashMap<>();
        voxelShapes.put("names", packet.getNameMap());
        voxelShapes.put("shapes", packet.getShapes());
        proxy.saveJson("voxel_shapes.json", voxelShapes);
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(JigsawStructureDataPacket packet) {
        proxy.saveCompressedNBT("jigsaw_structure_data", packet.getJigsawStructureDataTag());
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(SyncEntityPropertyPacket packet) {
        entityProperties.add(packet.getData());
        NbtMapBuilder root = NbtMap.builder();
        entityProperties.forEach(map -> root.put(map.getString("type"), map));
        proxy.saveCompressedNBT("entity_properties", root.build());
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(ItemRegistryPacket packet) {
        List<DataEntry> itemData = new ArrayList<>();

        NbtMapBuilder root = NbtMap.builder();
        for (var item : packet.getItems()) {
            // GearsMC/Protocol: ItemComponentPacket -> ItemRegistryPacket, erişimciler record biçiminde.
            if (item.componentData() != null) {
                root.putCompound(item.identifier(), item.componentData());
            }
            itemData.add(new DataEntry(item.identifier(), item.runtimeId(), item.version().ordinal(), item.componentBased()));
        }

        if (ProxyPass.CODEC.getProtocolVersion() >= 776) {
            for (DataEntry entry : itemData) {
                ProxyPass.legacyIdMap.put(entry.id(), entry.name());
            }

            DefinitionRegistry<ItemDefinition> itemDefinitions = ItemDefinitionRegistries.fromDefinitions(packet.getItems());

            this.session.getPeer().getCodecHelper().setItemDefinitions(itemDefinitions);
            if (player != null) {
                player.getUpstream().getPeer().getCodecHelper().setItemDefinitions(itemDefinitions);
            }

            itemData.sort(Comparator.comparing(o -> o.name));
            proxy.saveJson("runtime_item_states.json", itemData);
        }

        proxy.saveCompressedNBT("item_components", root.build());

        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(CraftingDataPacket packet) {
        RecipeUtils.writeRecipes(packet, this.proxy);
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(DisconnectPacket packet) {
        this.session.disconnect();
        // Let the client see the reason too.
        return PacketSignal.UNHANDLED;
    }

    private void dumpCreativeItems(List<CreativeItemGroup> groups, List<CreativeItemData> contents) {
        List<CreativeGroup> groupEntries = new ArrayList<>();
        for (CreativeItemGroup group : groups) {
            String categoryName = group.category().name().toLowerCase();
            String name = group.name();
            groupEntries.add(new CreativeGroup(name, categoryName, createCreativeItemEntry(group.icon())));
        }

        List<CreativeItemEntry> entries = new ArrayList<>();
        for (CreativeItemData content : contents) {
            entries.add(createCreativeItemEntry(content.item(), content.groupId()));
        }

        Map<String, Object> items = new HashMap<>();
        items.put("groups", groupEntries);
        items.put("items", entries);

        proxy.saveJson("creative_items.json", items);
    }

    private CreativeItemEntry createCreativeItemEntry(ItemData data, int groupId) {
        ItemEntry entry = createCreativeItemEntry(data);
        return new CreativeItemEntry(entry.getId(), entry.getDamage(), entry.getBlockRuntimeId(), entry.getBlockTag(), entry.getNbt(), groupId);
    }

    private ItemEntry createCreativeItemEntry(ItemData data) {
        ItemDefinition entry = data.getDefinition();
        String id = entry.identifier();
        Integer damage = data.getDamage() == 0 ? null : (int) data.getDamage();

        String blockTag = null;
        Integer blockRuntimeId = null;
        if (data.getBlockDefinition() instanceof NbtBlockDefinitionRegistry.NbtBlockDefinition definition) {
            blockTag = encodeNbtToString(definition.tag());
        } else if (data.getBlockDefinition() != null) {
            blockRuntimeId = data.getBlockDefinition().runtimeId();
        }

        NbtMap tag = data.getTag();
        String tagData = null;
        if (tag != null) {
            tagData = encodeNbtToString(tag);
        }
        return new ItemEntry(id, damage, blockRuntimeId, blockTag, tagData);
    }

    @Override
    public PacketSignal handle(CreativeContentPacket packet) {
        try {
            dumpCreativeItems(packet.getGroups(), packet.getContents());
        } catch (Exception e) {
            log.error("Failed to dump creative contents", e);
        }
        return PacketSignal.UNHANDLED;
    }

    private static Map<String, Integer> sortMap(Map<String, Integer> map) {
        return map.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (oldValue, newValue) -> oldValue,
                        LinkedHashMap::new));
    }

    private static String encodeNbtToString(NbtMap tag) {
        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
             NBTOutputStream stream = new NBTOutputStream(new LittleEndianDataOutputStream(byteArrayOutputStream))) {
            stream.writeTag(tag);
            return Base64.getEncoder().encodeToString(byteArrayOutputStream.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @EqualsAndHashCode(callSuper = true)
    @Value
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"id", "damage", "blockRuntimeId", "groupId", "block_state_b64", "nbt_b64"})
    private static class CreativeItemEntry extends ItemEntry {
        private final int groupId;

        public CreativeItemEntry(String id, Integer damage, Integer blockRuntimeId, String blockTag, String nbt, int groupId) {
            super(id, damage, blockRuntimeId, blockTag, nbt);
            this.groupId = groupId;
        }
    }

    @Data
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"id", "damage", "blockRuntimeId", "block_state_b64", "nbt_b64"})
    private static class ItemEntry {
        private final String id;
        private final Integer damage;
        private final Integer blockRuntimeId;
        @JsonProperty("block_state_b64")
        private final String blockTag;
        @JsonProperty("nbt_b64")
        private final String nbt;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"name", "category", "icon"})
    private record CreativeGroup(String name, String category, ItemEntry icon) {
    }

    @Value
    @JsonPropertyOrder({"name", "id", "data"})
    private static class RuntimeEntry {
        private static final Comparator<RuntimeEntry> COMPARATOR = Comparator.comparingInt(RuntimeEntry::getId)
                .thenComparingInt(RuntimeEntry::getData);

        private final String name;
        private final int id;
        private final int data;
    }

    @JsonPropertyOrder({"name", "id", "version", "componentBased"})
    private record DataEntry(String name, int id, int version, boolean componentBased) {
    }
}
