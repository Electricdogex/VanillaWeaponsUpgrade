package com.vanillaweapons.vwu;

import java.util.List;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

final class BeaconVisuals {
    static void render(Location location, boolean active, int ticks, String[] colors, List<BlockDisplay> parts) {
        String colorName = colors[0];
        int count = active ? 153 : 97;
        while (parts.size() > count) parts.removeLast().remove();
        Material colored = Material.valueOf(colorName + "_CONCRETE");
        while (parts.size() < count) {
            int index = parts.size();
            parts.add(location.getWorld().spawn(location.clone().add(0.5, 0.5, 0.5), BlockDisplay.class, display -> {
                display.setPersistent(false); display.setInterpolationDuration(index < 96 || index >= 151 ? 5 : 0); display.setViewRange(4);
                display.setDisplayWidth(12); display.setDisplayHeight(384); display.setShadowRadius(0);
                display.setBlock((index < 96 ? Material.BLACK_CONCRETE : colored).createBlockData());
                display.setBrightness(new Display.Brightness(index < 96 ? 0 : 15, index < 96 ? 0 : 15));
                display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.001f), new Quaternionf()));
            }));
        }
        for (int index = 0; index < parts.size(); index++) {
            BlockDisplay part = parts.get(index); if (!part.isValid()) continue;
            Vector3f center, scale; Quaternionf rotation;
            if (index == 96) {
                // The opaque display covers only the native cyan inner model, preserving its glass cage.
                part.setBlock(Material.COMMAND_BLOCK.createBlockData());
                part.setBrightness(new Display.Brightness(active ? 15 : 10, 15));
                center = new Vector3f(0, 0.08f, 0); scale = new Vector3f(0.76f); rotation = new Quaternionf();
            } else if (index < 96) {
                int ring = index / 48;
                double angle = (index % 48) * Math.PI * 2 / 48;
                float radius = ring == 0 ? 1.18f : 1.52f;
                Quaternionf orbit = new Quaternionf().rotateY(ticks * (ring == 0 ? 0.007f : -0.009f)).rotateX(ring == 0 ? 0.55f : -0.75f);
                center = orbit.transform(new Vector3f((float) Math.cos(angle) * radius, 0, (float) Math.sin(angle) * radius)).add(0, 1.35f, 0);
                scale = new Vector3f((float) (2 * radius * Math.sin(Math.PI / 48) * 1.12), 0.085f, 0.11f);
                rotation = new Quaternionf(orbit).rotateY((float) (-angle - Math.PI / 2));
            } else if (index < 147) {
                int beam = index - 97;
                int cluster = beam / 10;
                int column = (beam % 10) / 2; boolean core = beam % 2 == 1;
                float clusterX = cluster == 0 ? 0 : cluster % 2 == 1 ? -3.2f : 3.2f;
                float clusterZ = cluster == 0 ? 0 : cluster <= 2 ? -3.2f : 3.2f;
                float railX = column == 0 ? 0 : column % 2 == 1 ? -0.34f : 0.34f;
                float railZ = column == 0 ? 0 : column <= 2 ? -0.34f : 0.34f;
                float start = cluster == 0 ? 0.47f : surface(location, clusterX + railX, clusterZ + railZ);
                // Sea lanterns have an animated texture: stretching it into a beam creates travelling bands.
                part.setBlock((core && !colors[cluster].equals("BLACK") ? Material.WHITE_CONCRETE : Material.valueOf(colors[cluster] + "_CONCRETE")).createBlockData());
                float height = Math.max(1, location.getWorld().getMaxHeight() - location.getBlockY() - start - 0.5f);
                center = new Vector3f(clusterX + railX, start + height / 2, clusterZ + railZ);
                float width = column == 0 ? (core ? 0.05f : 0.2f) : (core ? 0.022f : 0.075f);
                scale = new Vector3f(width, height, width); rotation = new Quaternionf();
                if (cluster == 0 && column == 0) {
                    // The native center beam supplies this column. Do not cover it with an opaque full-height pillar.
                    part.setBlock(Material.AIR.createBlockData()); center.set(0,-5,0); scale.set(0.001f);
                }
            } else if (index < 151) {
                int emitter = index - 147;
                part.setBlock(Material.AIR.createBlockData());
                center = new Vector3f(emitter % 2 == 0 ? -3.2f : 3.2f, -5, emitter < 2 ? -3.2f : 3.2f);
                scale = new Vector3f(0.001f); rotation = new Quaternionf();
            } else {
                part.setBlock((index == 151 ? colored : Material.SEA_LANTERN).createBlockData());
                center = new Vector3f(0, 1.35f, 0); scale = new Vector3f(index == 151 ? 0.32f : 0.2f);
                rotation = new Quaternionf().rotateXYZ(ticks * 0.013f, ticks * -0.019f, ticks * 0.01f);
            }
            center.sub(rotation.transform(new Vector3f(scale).mul(0.5f)));
            Transformation next = new Transformation(center, rotation, scale, new Quaternionf());
            if (!next.equals(part.getTransformation())) { part.setInterpolationDelay(0); part.setTransformation(next); }
        }
        if (!active) return;
        Color color = color(colorName);
        Location core = location.clone().add(0.5, 1.85, 0.5);
        location.getWorld().spawnParticle(Particle.DUST, core, 12, 0.24, 0.24, 0.24, 0, new Particle.DustOptions(color, 0.9f));
        location.getWorld().spawnParticle(Particle.END_ROD, core, 2, 0.08, 0.08, 0.08, 0.015);
    }
    static Color color(String colorName) {
        return switch (colorName) {
            case "RED" -> Color.RED; case "BLUE" -> Color.BLUE; case "CYAN" -> Color.AQUA;
            case "LIME" -> Color.LIME; case "YELLOW" -> Color.YELLOW; case "ORANGE" -> Color.ORANGE;
            case "PINK" -> Color.fromRGB(243, 139, 170); case "WHITE" -> Color.WHITE;
            case "LIGHT_BLUE" -> Color.fromRGB(58, 179, 218); case "MAGENTA" -> Color.fromRGB(199, 78, 189);
            case "LIGHT_GRAY" -> Color.fromRGB(157, 157, 151); case "GRAY" -> Color.fromRGB(71, 79, 82);
            case "BLACK" -> Color.fromRGB(29, 29, 33); case "BROWN" -> Color.fromRGB(131, 84, 50);
            case "GREEN" -> Color.fromRGB(94, 124, 22); default -> Color.PURPLE;
        };
    }
    private static float surface(Location location, float offsetX, float offsetZ) {
        int blockX = (int) Math.floor(location.getX() + 0.5 + offsetX);
        int blockZ = (int) Math.floor(location.getZ() + 0.5 + offsetZ);
        if (!location.getWorld().isChunkLoaded(blockX >> 4, blockZ >> 4)) return -4.53f;
        for (int blockY = location.getBlockY(); blockY >= location.getWorld().getMinHeight(); blockY--) {
            if (location.getWorld().getBlockAt(blockX, blockY, blockZ).getType().isSolid()) {
                return (float) (blockY + 1 - (location.getY() + 0.5) - 0.03);
            }
        }
        return (float) (location.getWorld().getMinHeight() - location.getY() - 0.5);
    }
}
