package com.vanillaweapons.vwu;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** A downward-facing opening into a starfield, high above the beacon. */
final class CommandRifts {
    private static final NamespacedKey MARKER = new NamespacedKey("vanillaweaponsupgrade", "rift_display");
    private final Map<Location, Rift> states = new HashMap<>();
    private record Piece(Material material, float x, float y, float z, float w, float h, float d, float angle, int light, int motion) {}
    private static final class Rift {
        final List<Piece> geometry = scene();
        final List<BlockDisplay> entities = new ArrayList<>();
        final Quaternionf plane;
        int openedAt = -1, closingAt = -1;
        float progress, closingFrom, lastProgress = -1;
        boolean announced;
        Rift(float yaw) { plane = new Quaternionf().rotateY((float) Math.toRadians(-yaw)).rotateX((float) Math.PI / 2); }
    }
    boolean ready(Location home) {
        Rift rift = states.get(home);
        return rift != null && rift.closingAt < 0 && rift.progress >= 0.999f && rift.entities.size() == rift.geometry.size()
                && rift.entities.stream().allMatch(Entity::isValid);
    }
    void remove(Location home) {
        Rift rift = states.remove(home); if (rift != null) for (Entity entity : rift.entities) entity.remove();
    }
    void render(Location home, boolean wanted, int ticks, float yaw) {
        Rift rift = states.get(home);
        if (rift != null && rift.entities.stream().anyMatch(entity -> !entity.isValid())) { remove(home); rift = null; }
        if (wanted && rift == null && states.size() < 2) {
            rift = new Rift(yaw); states.put(home.clone(), rift);
            sound(home, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.55f, 0.55f);
        }
        if (rift == null) return;
        if (!wanted && rift.closingAt < 0) { rift.closingAt = ticks; rift.closingFrom = rift.progress; sound(home, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.45f, 0.7f); }
        if (wanted && rift.closingAt >= 0) { rift.closingAt = -1; rift.openedAt = ticks - (int) (rift.progress * 40); }
        if (wanted) {
            // Limit the entity creation burst; the rift stays closed until its scene is complete.
            for (int budget = 0; budget < 32 && rift.entities.size() < rift.geometry.size(); budget++) {
                Piece piece = rift.geometry.get(rift.entities.size());
                rift.entities.add(home.getWorld().spawn(home.clone().add(0.5, 0.5, 0.5), BlockDisplay.class, display -> {
                    display.setPersistent(false); display.setInterpolationDuration(5); display.setViewRange(8);
                    display.setDisplayWidth(180); display.setDisplayHeight(home.getWorld().getMaxHeight()-home.getWorld().getMinHeight()+32); display.setShadowRadius(0);
                    display.setBrightness(new Display.Brightness(piece.light, piece.light == 0 ? 0 : 15));
                    display.setBlock(piece.material.createBlockData());
                    display.getPersistentDataContainer().set(MARKER, PersistentDataType.BYTE, (byte) 1);
                    display.setTransformation(new Transformation(new Vector3f(0, height(home), 0), new Quaternionf(), new Vector3f(0.001f), new Quaternionf()));
                }));
            }
            if (rift.entities.size() == rift.geometry.size() && rift.openedAt < 0) rift.openedAt = ticks;
            float age = rift.openedAt < 0 ? 0 : Math.min(1, (ticks - rift.openedAt) / 40f);
            rift.progress = age * age * (3 - 2 * age);
        } else {
            rift.progress = rift.closingFrom * Math.max(0, 1 - (ticks - rift.closingAt) / 20f);
            if (ticks - rift.closingAt >= 20) { remove(home); return; }
        }
        if (rift.progress >= 0.999f && !rift.announced) { rift.announced = true; sound(home, Sound.BLOCK_END_PORTAL_SPAWN, 0.55f, 0.7f); }
        for (int index = 0; index < rift.entities.size(); index++) {
            Piece p = rift.geometry.get(index);
            if (p.motion == 0 && rift.progress == rift.lastProgress) continue;
            Vector3f center = new Vector3f(p.x, p.y, p.z), scale = new Vector3f(p.w, p.h, p.d);
            Quaternionf local = new Quaternionf().rotateZ(p.angle);
            float open = Math.max(0.001f, rift.progress);
            center.x *= open; center.y *= 0.15f + 0.85f * open;
            scale.x *= open; scale.y *= 0.15f + 0.85f * open;
            Quaternionf rotation = new Quaternionf(rift.plane).mul(local);
            center = rift.plane.transform(center).add(0, height(home), 0);
            if (p.motion == 2) {
                float phase = ticks * 0.05f + index * 1.0472f;
                float travel = ((ticks + index * 17) % 160) / 160f;
                center.set((float)Math.cos(phase)*(0.4f+travel), 3+travel*(height(home)-6), (float)Math.sin(phase)*(0.4f+travel));
                scale.set(0.065f*open, (1.2f+travel)*open, 0.065f*open); rotation = new Quaternionf();
            }
            if (p.motion == 3) {
                // Ground-to-sky industrial spine: staggered black rails and a narrow cyan reactor channel.
                center.set(p.x*open,3+p.y*(height(home)-8),p.z*open);
                scale.set(p.w*open,p.h*(height(home)-8)*open,p.d*open);
                rotation = new Quaternionf().rotateY(p.angle);
            }
            center.sub(rotation.transform(new Vector3f(scale).mul(0.5f)));
            BlockDisplay display = rift.entities.get(index);
            display.setInterpolationDelay(0);
            display.setTransformation(new Transformation(center, rotation, scale, new Quaternionf()));
        }
        rift.lastProgress = rift.progress;
        if (ticks % 10 == 0) {
            Color color = rift.progress < 0.9 ? Color.fromRGB(255, 132, 54) : Color.fromRGB(65, 240, 220);
            float x = (float)Math.sin(ticks * 0.025) * 62 * rift.progress;
            Vector3f offset = rift.plane.transform(new Vector3f(x, ticks%20==0?upper(x):lower(x), 0)).add(0, height(home), 0);
            Location at = home.clone().add(0.5 + offset.x, 0.5 + offset.y, 0.5 + offset.z);
            home.getWorld().spawnParticle(Particle.DUST, at, 14, 0.35, 0.5, 0.35, 0, new Particle.DustOptions(color, 1f));
            home.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, at, 5, 0.5, 0.5, 0.5, 0.03);
        }
        if (ticks % 160 == 0 && rift.progress > 0.9) sound(home, Sound.BLOCK_PORTAL_AMBIENT, 0.25f, 0.5f);
    }
    private static void sound(Location home, Sound sound, float volume, float pitch) {
        for (Player player : home.getWorld().getPlayers()) if (player.getLocation().distanceSquared(home) < 96 * 96) player.playSound(home, sound, volume, pitch);
    }
    static float height(Location home) { return (float)Math.max(8, home.getWorld().getMaxHeight()-home.getY()-16); }
    private static float lower(float x) { return 0.07f*x-envelope(x); }
    private static float upper(float x) { return 0.07f*x+envelope(x); }
    private static float envelope(float x) { return (float)(27*Math.sqrt(Math.max(0,1-x*x/4096))+0.55*Math.sin(x*1.25)); }
    private static void box(List<Piece> pieces, Material material, double x, double y, double z, double w, double h, double d, int light) {
        pieces.add(new Piece(material, (float)x, (float)y, (float)z, (float)w, (float)h, (float)d, 0, light, 0));
    }
    private static void line(List<Piece> pieces, Material material, double ax, double ay, double bx, double by, double z, double width, double depth, int light) {
        pieces.add(new Piece(material, (float)((ax+bx)/2), (float)((ay+by)/2), (float)z,
                (float)Math.hypot(bx-ax,by-ay) + 0.04f, (float)width, (float)depth, (float)Math.atan2(by-ay,bx-ax), light, 0));
    }
    private static List<Piece> scene() {
        List<Piece> pieces = new ArrayList<>(); Random random = new Random(0x535441524649454cL);
        // A 128-block-wide black aperture; every point of light stays inside its silhouette.
        for(int i=0;i<64;i++) {
            float x=-63+i*2, lo=lower(x), hi=upper(x);
            box(pieces,Material.BLACK_CONCRETE,x,(lo+hi)/2,-0.15,2.035,hi-lo,0.18,0);
            float ax=-64+i*2,bx=ax+2;
            Material rim=i%5==0?Material.SEA_LANTERN:Material.CRYING_OBSIDIAN;
            line(pieces,rim,ax,upper(ax),bx,upper(bx),0,0.16,0.18,15);
            line(pieces,rim,ax,lower(ax),bx,lower(bx),0,0.14,0.18,15);
        }
        List<float[]> stars=new ArrayList<>();
        for(int attempts=0;stars.size()<128 && attempts<20000;attempts++) {
            float x=-61+random.nextFloat()*122, y=-26+random.nextFloat()*52;
            if(y<lower(x)+1 || y>upper(x)-1) continue;
            boolean crowded=false;
            for(float[] star:stars) if(Math.hypot(x-star[0],y-star[1])<2.2) {crowded=true;break;}
            if(crowded)continue;
            stars.add(new float[]{x,y});
            float size=0.15f+random.nextFloat()*0.27f;
            Material material=stars.size()%11==0?Material.OCHRE_FROGLIGHT:stars.size()%4==0?Material.PEARLESCENT_FROGLIGHT:Material.SEA_LANTERN;
            box(pieces,material,x,y,0.05+random.nextFloat()*0.12,size,size,0.055,15);
        }
        // Eight brighter stars provide a few recognizable constellations, without a planet or landscape.
        for(int i=0;i<8;i++) {
            float[] star=stars.get(i*15);
            box(pieces,Material.PEARLESCENT_FROGLIGHT,star[0],star[1],0.24,0.42,0.42,0.065,15);
            box(pieces,Material.SEA_LANTERN,star[0],star[1],0.26,1.0,0.055,0.045,15);
            box(pieces,Material.SEA_LANTERN,star[0],star[1],0.26,0.055,1.0,0.045,15);
        }
        for(int i=0;i<6;i++)pieces.add(new Piece(Material.SEA_LANTERN,0,0,0,0.065f,1,0.065f,0,15,2));
        for(int tier=0;tier<5;tier++) {
            float radius=3.4f-tier*0.5f;
            for(int side=0;side<4;side++) {
                float angle=(float)(side*Math.PI/2),x=(float)Math.cos(angle)*radius,z=(float)Math.sin(angle)*radius;
                pieces.add(new Piece(Material.BLACK_CONCRETE,x,(tier+0.5f)/5,z,0.42f,0.17f,0.65f,angle,0,3));
            }
        }
        for(int side=0;side<4;side++) {
            float angle=(float)(side*Math.PI/2);
            pieces.add(new Piece(Material.LIGHT_BLUE_CONCRETE,(float)Math.cos(angle)*0.65f,0.5f,(float)Math.sin(angle)*0.65f,0.055f,0.94f,0.055f,0,15,3));
            pieces.add(new Piece(Material.BLACK_CONCRETE,(float)Math.cos(angle)*2.3f,0.965f,(float)Math.sin(angle)*2.3f,0.8f,0.06f,2.0f,angle,0,3));
        }
        pieces.add(new Piece(Material.LIGHT_BLUE_CONCRETE,0,0.5f,0,0.22f,1,0.22f,0,15,3));
        pieces.add(new Piece(Material.SEA_LANTERN,0,0.5f,0,0.065f,1,0.065f,0,15,3));
        if(pieces.size()>400)throw new IllegalStateException("Rift entity budget exceeded");
        return List.copyOf(pieces);
    }
}
