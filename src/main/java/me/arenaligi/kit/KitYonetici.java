package me.arenaligi.kit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import me.arenaligi.ArenaLigi;
import me.arenaligi.Mesaj;
import me.arenaligi.veri.EsyaKodu;
import me.arenaligi.veri.YamlDosya;

/**
 * Kit havuzu: config.yml'deki "hazir-kitler" + oyun içinden eklenenler (kitler.yml).
 * Admin kendi üzerindeki eşyalarla kit tanımlayabilir (envanter + zırh + offhand, büyüler dahil); aynı adlı hazır kitin yerine geçer.
 * Her maçta havuzdan rastgele bir kit seçilir ve iki dövüşçüye de aynısı verilir.
 * Verilen her eşya PDC ile "kit eşyası" olarak işaretlenir: arenadan dışarı çıkamaz, maç sonunda/girişte silinir.
 */
public class KitYonetici {

    private final ArenaLigi plugin;
    private final YamlDosya dosya;
    private final Map<String, ItemStack[]> kitler = new LinkedHashMap<>();
    private final Map<String, String> okunamayan = new LinkedHashMap<>(); // Bozuk kitler ham haliyle korunur
    private final Map<String, ItemStack[]> hazirKitler = new LinkedHashMap<>(); // config.yml -> hazir-kitler
    private final NamespacedKey kitKey;

    public KitYonetici(ArenaLigi plugin) {
        this.plugin = plugin;
        this.kitKey = new NamespacedKey(plugin, "kit_esyasi");
        this.dosya = new YamlDosya(plugin, "kitler.yml", this::olustur);
        YamlConfiguration y = dosya.oku();
        ConfigurationSection bolum = y.getConfigurationSection("kitler");
        if (bolum != null) {
            for (String ad : bolum.getKeys(false)) {
                try {
                    kitler.put(ad, EsyaKodu.oku(bolum.getString(ad, "")));
                } catch (RuntimeException e) {
                    okunamayan.put(ad, bolum.getString(ad, ""));
                    plugin.getLogger().warning("[Kit] '" + ad + "' kiti okunamadı (dosyada korunuyor): " + e.getMessage());
                }
            }
        }
        hazirKitleriYukle();
    }

    private Mesaj m() { return plugin.mesaj(); }

    // ------------------------------------------------------------------ HAZIR KİTLER (config)
    // Slotlar (PlayerInventory.getContents): 0-35 envanter, 36 bot, 37 pantolon, 38 göğüslük, 39 kask, 40 offhand
    private static final String[] ZIRH_PARCALARI = {"_BOOTS", "_LEGGINGS", "_CHESTPLATE", "_HELMET"};

    /** config.yml'deki hazır kitleri (yeniden) okur. /arena admin yenile ile de çağrılır. */
    public void hazirKitleriYukle() {
        hazirKitler.clear();
        ConfigurationSection bolum = plugin.getConfig().getConfigurationSection("hazir-kitler");
        if (bolum == null) return;
        for (String ad : bolum.getKeys(false)) {
            ConfigurationSection c = bolum.getConfigurationSection(ad);
            if (c == null) continue;
            ItemStack[] kit = new ItemStack[41];
            String zirh = c.getString("zirh");
            if (zirh != null) {
                for (int i = 0; i < 4; i++) {
                    Material mat = Material.matchMaterial(zirh.toUpperCase(Locale.ROOT) + ZIRH_PARCALARI[i]);
                    if (mat == null) { plugin.getLogger().warning("[Kit] " + ad + ": bilinmeyen zırh türü " + zirh); break; }
                    kit[36 + i] = esyaOku(ad, mat.name() + " 1 " + c.getString("zirh-buyu", ""));
                }
            }
            int slot = 0;
            for (String satir : c.getStringList("esyalar")) {
                if (slot > 35) break;
                ItemStack e = esyaOku(ad, satir);
                if (e != null) kit[slot++] = e;
            }
            if (c.getString("offhand") != null) kit[40] = esyaOku(ad, c.getString("offhand"));
            hazirKitler.put(ad.toLowerCase(Locale.ROOT), kit);
        }
    }

    /** "DIAMOND_SWORD", "COOKED_BEEF 8", "BOW 1 power:2,unbreaking:1" */
    private ItemStack esyaOku(String kit, String satir) {
        String[] p = satir.trim().split("\\s+");
        Material mat = p.length > 0 ? Material.matchMaterial(p[0]) : null;
        if (mat == null || !mat.isItem()) { plugin.getLogger().warning("[Kit] " + kit + ": bilinmeyen eşya '" + satir + "'"); return null; }
        int adet = 1;
        if (p.length > 1) try { adet = Math.max(1, Math.min(mat.getMaxStackSize(), Integer.parseInt(p[1]))); } catch (NumberFormatException ignored) {}
        ItemStack item = new ItemStack(mat, adet);
        if (p.length > 2) {
            for (String b : p[2].split(",")) {
                String[] kv = b.split(":");
                org.bukkit.enchantments.Enchantment e = kv.length == 2
                        ? org.bukkit.Registry.ENCHANTMENT.get(NamespacedKey.minecraft(kv[0].toLowerCase(Locale.ROOT))) : null;
                if (e == null) { plugin.getLogger().warning("[Kit] " + kit + ": bilinmeyen büyü '" + b + "'"); continue; }
                try { item.addUnsafeEnchantment(e, Math.max(1, Integer.parseInt(kv[1]))); } catch (NumberFormatException ignored) {}
            }
        }
        return item;
    }

    private ItemStack[] kitBul(String ad) {
        ItemStack[] k = kitler.get(ad);
        return k != null ? k : hazirKitler.get(ad);
    }

    private YamlConfiguration olustur() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, String> e : okunamayan.entrySet()) if (!kitler.containsKey(e.getKey())) y.set("kitler." + e.getKey(), e.getValue());
        for (Map.Entry<String, ItemStack[]> e : kitler.entrySet()) {
            y.set("kitler." + e.getKey(), EsyaKodu.yaz(e.getValue()));
        }
        return y;
    }

    public void hemenKaydet() { dosya.hemenKaydet(); }

    /** Havuzdaki tüm kitler (hazır + oyun içi, aynı ad bir kez). */
    public List<String> adlar() {
        java.util.LinkedHashSet<String> s = new java.util.LinkedHashSet<>(hazirKitler.keySet());
        s.addAll(kitler.keySet());
        return new ArrayList<>(s);
    }
    public boolean bosMu() { return kitler.isEmpty() && hazirKitler.isEmpty(); }

    /** Havuzdan rastgele kit adı (havuz boşsa null). */
    public String rastgele() {
        if (bosMu()) return null;
        List<String> l = adlar();
        return l.get(ThreadLocalRandom.current().nextInt(l.size()));
    }

    // ------------------------------------------------------------------ KİT EŞYASI
    public boolean kitEsyasiMi(ItemStack item) {
        return item != null && item.getType() != Material.AIR && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(kitKey, PersistentDataType.BYTE);
    }

    private ItemStack isaretle(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return null;
        ItemStack k = item.clone();
        ItemMeta meta = k.getItemMeta();
        if (meta == null) return k;
        meta.getPersistentDataContainer().set(kitKey, PersistentDataType.BYTE, (byte) 1);
        k.setItemMeta(meta);
        return k;
    }

    /**
     * Kiti oyuncuya giydirir (envanter + zırh + offhand). Oyuncunun envanteri önceden yedeklenip boşaltılmış olmalı.
     * @return kit bulunduysa true
     */
    public boolean ver(Player p, String ad) {
        ItemStack[] kit = kitBul(ad);
        if (kit == null) return false;
        ItemStack[] icerik = new ItemStack[p.getInventory().getSize()];
        for (int i = 0; i < Math.min(kit.length, icerik.length); i++) icerik[i] = isaretle(kit[i]);
        p.getInventory().setContents(icerik);
        p.updateInventory();
        return true;
    }

    /** Oyuncunun envanterinden, imlecinden ve ender sandığından kit eşyalarını siler. Silinen adet. */
    public int temizle(Player p) {
        int sayi = 0;
        ItemStack[] icerik = p.getInventory().getContents();
        for (int i = 0; i < icerik.length; i++) {
            if (kitEsyasiMi(icerik[i])) { p.getInventory().setItem(i, null); sayi++; }
        }
        if (kitEsyasiMi(p.getItemOnCursor())) { p.setItemOnCursor(null); sayi++; }
        ItemStack[] ender = p.getEnderChest().getContents();
        for (int i = 0; i < ender.length; i++) {
            if (kitEsyasiMi(ender[i])) { p.getEnderChest().setItem(i, null); sayi++; }
        }
        return sayi;
    }

    // ------------------------------------------------------------------ ADMIN: /arena admin kit ...
    public void komut(Player p, String[] args) {
        String islem = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "liste";
        String ad = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : null;
        switch (islem) {
            case "ekle" -> {
                if (ad == null || !ad.matches("[a-z0-9_-]{1,24}")) {
                    m().gonder(p, "kullanim", "&cKullanım: &e{kullanim}", "kullanim", "/arena admin kit ekle <ad> &7(üzerindeki eşyalar kit olur)");
                    return;
                }
                ItemStack[] icerik = p.getInventory().getContents();
                boolean bos = true;
                ItemStack[] kopya = new ItemStack[icerik.length];
                for (int i = 0; i < icerik.length; i++) {
                    if (icerik[i] != null && icerik[i].getType() != Material.AIR) {
                        if (kitEsyasiMi(icerik[i])) {
                            m().gonder(p, "kit-kit-esyasi", "&cÜzerinde başka bir kitin eşyası var; önce temizleyin.");
                            return;
                        }
                        kopya[i] = icerik[i].clone();
                        bos = false;
                    }
                }
                if (bos) { m().gonder(p, "kit-bos", "&cÜzerinizde eşya yok. Kiti giyinip tekrar deneyin."); return; }
                boolean vardi = kitler.containsKey(ad);
                kitler.put(ad, kopya);
                dosya.kaydet();
                m().gonder(p, vardi ? "kit-guncellendi" : "kit-eklendi", vardi ? "&a{ad} kiti güncellendi." : "&a{ad} kiti eklendi. &7(Havuzda {sayi} kit)",
                        "ad", ad, "sayi", adlar().size());
            }
            case "sil" -> {
                if (ad != null && !kitler.containsKey(ad) && hazirKitler.containsKey(ad)) {
                    m().gonder(p, "kit-hazir-silinemez", "&c{ad} config.yml'deki hazır bir kit; config'ten (hazir-kitler) silin.", "ad", ad);
                    return;
                }
                if (ad == null || kitler.remove(ad) == null) { m().gonder(p, "kit-yok", "&cBöyle bir kit yok."); return; }
                dosya.kaydet();
                m().gonder(p, "kit-silindi", "&e{ad} kiti silindi.", "ad", ad);
            }
            default -> {
                if (bosMu()) { m().gonder(p, "kit-liste-bos", "&7Kit havuzu boş. Eklemek için: &f/arena admin kit ekle <ad>"); return; }
                m().gonder(p, "kit-liste", "&6Kit havuzu ({sayi}): &f{liste}", "sayi", adlar().size(), "liste", String.join(", ", adlar()));
                m().gonder(p, "kit-liste-not", "&7(Hazır kitler config.yml'den, eklenenler kitler.yml'den; aynı ad varsa eklenen geçerli)");
            }
        }
    }
}
