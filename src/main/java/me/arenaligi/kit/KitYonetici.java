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
 * Kit havuzu (kitler.yml). Admin kendi üzerindeki eşyalarla kit tanımlar (envanter + zırh + offhand, büyüler dahil).
 * Her maçta havuzdan rastgele bir kit seçilir ve iki dövüşçüye de aynısı verilir.
 * Verilen her eşya PDC ile "kit eşyası" olarak işaretlenir: arenadan dışarı çıkamaz, maç sonunda/girişte silinir.
 */
public class KitYonetici {

    private final ArenaLigi plugin;
    private final YamlDosya dosya;
    private final Map<String, ItemStack[]> kitler = new LinkedHashMap<>();
    private final Map<String, String> okunamayan = new LinkedHashMap<>(); // Bozuk kitler ham haliyle korunur
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
    }

    private Mesaj m() { return plugin.mesaj(); }

    private YamlConfiguration olustur() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, String> e : okunamayan.entrySet()) if (!kitler.containsKey(e.getKey())) y.set("kitler." + e.getKey(), e.getValue());
        for (Map.Entry<String, ItemStack[]> e : kitler.entrySet()) {
            y.set("kitler." + e.getKey(), EsyaKodu.yaz(e.getValue()));
        }
        return y;
    }

    public void hemenKaydet() { dosya.hemenKaydet(); }

    public List<String> adlar() { return new ArrayList<>(kitler.keySet()); }
    public boolean bosMu() { return kitler.isEmpty(); }

    /** Havuzdan rastgele kit adı (havuz boşsa null). */
    public String rastgele() {
        if (kitler.isEmpty()) return null;
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
        ItemStack[] kit = kitler.get(ad);
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
                        "ad", ad, "sayi", kitler.size());
            }
            case "sil" -> {
                if (ad == null || kitler.remove(ad) == null) { m().gonder(p, "kit-yok", "&cBöyle bir kit yok."); return; }
                dosya.kaydet();
                m().gonder(p, "kit-silindi", "&e{ad} kiti silindi.", "ad", ad);
            }
            default -> {
                if (kitler.isEmpty()) { m().gonder(p, "kit-liste-bos", "&7Kit havuzu boş. Eklemek için: &f/arena admin kit ekle <ad>"); return; }
                m().gonder(p, "kit-liste", "&6Kit havuzu ({sayi}): &f{liste}", "sayi", kitler.size(), "liste", String.join(", ", kitler.keySet()));
            }
        }
    }
}
