package me.arenaligi;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Konumlar "dünya;x;y;z;yaw;pitch" metni olarak saklanır (Klan-Sistemi'ndeki yöntem): dünyası yüklü olmayan
 * bir konum dosyanın geri kalanının yüklenmesini bozmaz.
 */
public final class Konum {

    private Konum() {}

    public static String yaz(Location l) {
        if (l == null || l.getWorld() == null) return null;
        return l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch();
    }

    /** Dünya yüklü değilse ya da metin bozuksa null. */
    public static Location oku(String metin) {
        if (metin == null) return null;
        String[] p = metin.split(";");
        if (p.length < 4) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        try {
            return new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    p.length > 4 ? Float.parseFloat(p[4]) : 0f, p.length > 5 ? Float.parseFloat(p[5]) : 0f);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Konum iki köşe arasındaki kutunun içinde mi? (farklı dünya = dışarıda) */
    public static boolean kutuda(Location loc, Location a, Location b) {
        if (a == null || b == null || loc == null || loc.getWorld() == null || !loc.getWorld().equals(a.getWorld())) return false;
        return arada(loc.getX(), a.getX(), b.getX()) && arada(loc.getY(), a.getY(), b.getY()) && arada(loc.getZ(), a.getZ(), b.getZ());
    }

    private static boolean arada(double d, double a, double b) {
        return d >= Math.min(a, b) && d <= Math.max(a, b) + 1;
    }
}
