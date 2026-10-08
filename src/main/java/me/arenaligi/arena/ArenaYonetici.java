package me.arenaligi.arena;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

import me.arenaligi.ArenaLigi;
import me.arenaligi.Konum;
import me.arenaligi.Mesaj;
import me.arenaligi.veri.YamlDosya;

/**
 * Arena, bekleme salonu ve tribün tanımları (arena.yml) ve koruması.
 * - Arena: pos1/pos2 sınırları, spawn1/spawn2 doğma noktaları.
 * - Bekleme salonu: maça çağrılan dövüşçülerin bahis penceresinde beklediği nokta.
 * - Tribün: pos1/pos2 sınırları ve seyircinin ışınlandığı nokta. Tribünde hasar/PvP kapalı.
 * - Arenada ve tribünde blok kırma/koyma kapalı (arena.admin hariç).
 * Dünyası yüklü olmayan konumlar ham metin olarak korunur, kayıtta silinmez.
 */
public class ArenaYonetici implements Listener {

    /** Ayarlanabilir noktalar (komut adı -> açıklama). */
    public static final Map<String, String> NOKTALAR = new LinkedHashMap<>();
    static {
        NOKTALAR.put("arena-pos1", "Arena sınırı köşe 1");
        NOKTALAR.put("arena-pos2", "Arena sınırı köşe 2");
        NOKTALAR.put("spawn1", "1. dövüşçünün doğma noktası");
        NOKTALAR.put("spawn2", "2. dövüşçünün doğma noktası");
        NOKTALAR.put("bekleme", "Bekleme salonu");
        NOKTALAR.put("tribun-pos1", "Tribün sınırı köşe 1");
        NOKTALAR.put("tribun-pos2", "Tribün sınırı köşe 2");
        NOKTALAR.put("tribun", "Seyircinin ışınlandığı nokta");
    }

    private final ArenaLigi plugin;
    private final YamlDosya dosya;
    private final Map<String, String> ham = new LinkedHashMap<>(); // nokta -> "dünya;x;y;z;yaw;pitch"

    public ArenaYonetici(ArenaLigi plugin) {
        this.plugin = plugin;
        this.dosya = new YamlDosya(plugin, "arena.yml", this::olustur);
        YamlConfiguration y = dosya.oku();
        for (String n : NOKTALAR.keySet()) {
            String v = y.getString("noktalar." + n);
            if (v != null) ham.put(n, v);
        }
    }

    private Mesaj m() { return plugin.mesaj(); }

    private YamlConfiguration olustur() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, String> e : ham.entrySet()) y.set("noktalar." + e.getKey(), e.getValue());
        return y;
    }

    public void hemenKaydet() { dosya.hemenKaydet(); }

    public Location nokta(String ad) { return Konum.oku(ham.get(ad)); }

    public Location spawn1() { return nokta("spawn1"); }
    public Location spawn2() { return nokta("spawn2"); }
    public Location bekleme() { return nokta("bekleme"); }
    public Location tribun() { return nokta("tribun"); }

    public boolean arenadaMi(Location l) { return Konum.kutuda(l, nokta("arena-pos1"), nokta("arena-pos2")); }
    public boolean tribundeMi(Location l) { return Konum.kutuda(l, nokta("tribun-pos1"), nokta("tribun-pos2")); }

    /** Maç için gereken eksik noktalar (boşsa arena hazır). Dünyası yüklü olmayan nokta da eksik sayılır. */
    public String eksikler() {
        StringBuilder sb = new StringBuilder();
        for (String n : NOKTALAR.keySet()) if (nokta(n) == null) sb.append(n).append(' ');
        Location a = nokta("arena-pos1"), b = nokta("arena-pos2");
        if (a != null && b != null && !a.getWorld().equals(b.getWorld())) sb.append("(arena köşeleri farklı dünyada) ");
        Location s1 = spawn1(), s2 = spawn2();
        if (s1 != null && !arenadaMi(s1)) sb.append("(spawn1 arena sınırı dışında) ");
        if (s2 != null && !arenadaMi(s2)) sb.append("(spawn2 arena sınırı dışında) ");
        return sb.toString().trim();
    }

    public boolean hazir() { return eksikler().isEmpty(); }

    // ------------------------------------------------------------------ KOMUT: /arena admin kur <nokta>
    public void kur(Player p, String nokta) {
        String n = nokta.toLowerCase(Locale.ROOT);
        if (!NOKTALAR.containsKey(n)) {
            m().gonder(p, "kullanim", "&cKullanım: &e{kullanim}", "kullanim", "/arena admin kur <" + String.join("|", NOKTALAR.keySet()) + "|npc>");
            return;
        }
        Location l = p.getLocation();
        // Sınır köşeleri blok hizasına oturur; doğma/ışınlanma noktaları bakış yönüyle aynen saklanır
        if (n.endsWith("pos1") || n.endsWith("pos2")) l = l.getBlock().getLocation();
        ham.put(n, Konum.yaz(l));
        dosya.kaydet();
        m().gonder(p, "nokta-ayarlandi", "&a{nokta} ayarlandı &7({aciklama})", "nokta", n, "aciklama", NOKTALAR.get(n));
        String eksik = eksikler();
        if (eksik.isEmpty()) m().gonder(p, "arena-hazir", "&aArena kurulumu tamam!");
        else m().gonder(p, "arena-eksik", "&eEksik: &f{eksik}", "eksik", eksik);
    }

    public void durum(org.bukkit.command.CommandSender s) {
        m().gonder(s, "arena-durum-baslik", "&6--- Arena Kurulumu ---");
        for (Map.Entry<String, String> e : NOKTALAR.entrySet()) {
            boolean var = nokta(e.getKey()) != null;
            s.sendMessage(m().metin("arena-durum-satir", "{durum} &e{nokta} &7- {aciklama}", "durum", var ? "&a✔" : (ham.containsKey(e.getKey()) ? "&6?" : "&c✖"),
                    "nokta", e.getKey(), "aciklama", e.getValue()));
        }
        String eksik = eksikler();
        if (eksik.isEmpty()) m().gonder(s, "arena-hazir", "&aArena kurulumu tamam!");
        else m().gonder(s, "arena-eksik", "&eEksik: &f{eksik}", "eksik", eksik);
    }

    // ------------------------------------------------------------------ KORUMA
    private boolean korumali(Location l) { return arenadaMi(l) || tribundeMi(l); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onKir(BlockBreakEvent event) {
        if (korumali(event.getBlock().getLocation()) && !event.getPlayer().hasPermission("arena.admin")) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onKoy(BlockPlaceEvent event) {
        if (korumali(event.getBlock().getLocation()) && !event.getPlayer().hasPermission("arena.admin")) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onKovaBosalt(PlayerBucketEmptyEvent event) {
        if (korumali(event.getBlock().getLocation()) && !event.getPlayer().hasPermission("arena.admin")) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onKovaDoldur(PlayerBucketFillEvent event) {
        if (korumali(event.getBlock().getLocation()) && !event.getPlayer().hasPermission("arena.admin")) event.setCancelled(true);
    }

    /** Tribündeki oyuncu hasar almaz; tribünden de kimseye hasar verilemez (ok dahil). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHasar(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p && tribundeMi(p.getLocation())) {
            event.setCancelled(true);
            return;
        }
        if (event instanceof EntityDamageByEntityEvent e) {
            Entity vuran = e.getDamager();
            if (vuran instanceof Projectile pr && pr.getShooter() instanceof Entity atan) vuran = atan;
            if (vuran instanceof Player p && tribundeMi(p.getLocation())) event.setCancelled(true);
        }
    }
}
