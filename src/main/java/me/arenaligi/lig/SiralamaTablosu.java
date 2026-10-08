package me.arenaligi.lig;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;

import me.arenaligi.ArenaLigi;
import me.arenaligi.Konum;
import me.arenaligi.Mesaj;
import me.arenaligi.model.Dovuscu;
import me.arenaligi.veri.YamlDosya;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Dünyada duran sıralama tablosu (yüzen yazı, TextDisplay): ilk 10 dövüşçü, lig ve puan.
 * /arena admin kur tablo ile bakılan yere kurulur, her maçtan sonra ve dakikada bir güncellenir.
 * Yazı varlığı kalıcıdır; chunk'ın varlıkları yüklenince bulunup güncellenir, kaybolduysa yeniden oluşturulur.
 */
public class SiralamaTablosu implements Listener {

    private final ArenaLigi plugin;
    private final NamespacedKey tabloKey;
    private final YamlDosya dosya;
    private String konum;   // "dünya;x;y;z;yaw;pitch"
    private UUID varlik;    // TextDisplay'in UUID'si

    public SiralamaTablosu(ArenaLigi plugin) {
        this.plugin = plugin;
        this.tabloKey = new NamespacedKey(plugin, "siralama_tablosu");
        this.dosya = new YamlDosya(plugin, "tablo.yml", this::olustur);
        YamlConfiguration y = dosya.oku();
        konum = y.getString("konum");
        String v = y.getString("varlik");
        varlik = v == null ? null : UUID.fromString(v);
        Bukkit.getScheduler().runTaskTimer(plugin, this::guncelle, 100L, 1200L);
    }

    private Mesaj m() { return plugin.mesaj(); }

    private YamlConfiguration olustur() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("konum", konum);
        y.set("varlik", varlik == null ? null : varlik.toString());
        return y;
    }

    public void kur(Player p) {
        kaldir();
        Location l = p.getLocation().clone().add(0, 2.2, 0);
        l.setPitch(0);
        konum = Konum.yaz(l);
        olusturVarlik(l);
        dosya.hemenKaydet();
        m().gonder(p, "tablo-kuruldu", "&aSıralama tablosu kuruldu. &7Kaldırmak için: &f/arena admin tablo sil");
    }

    public void sil(org.bukkit.command.CommandSender s) {
        kaldir();
        konum = null;
        dosya.hemenKaydet();
        m().gonder(s, "tablo-silindi", "&eSıralama tablosu kaldırıldı.");
    }

    private void kaldir() {
        Entity e = varlik == null ? null : Bukkit.getEntity(varlik);
        if (e != null) e.remove();
        varlik = null;
    }

    private void olusturVarlik(Location l) {
        TextDisplay t = l.getWorld().spawn(l, TextDisplay.class, d -> {
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(true);
            d.setShadowed(true);
            d.setLineWidth(400);
            d.getPersistentDataContainer().set(tabloKey, PersistentDataType.BYTE, (byte) 1);
        });
        varlik = t.getUniqueId();
        yaz(t);
    }

    private String metin() {
        List<String> satirlar = new ArrayList<>();
        satirlar.add(m().metin("tablo-baslik", "&6&l⚔ ARENA LİGİ ⚔"));
        satirlar.add(m().metin("tablo-alt-baslik", "&7İlk 10 Dövüşçü"));
        List<Dovuscu> liste = plugin.dovusculer().siralama();
        if (liste.isEmpty()) satirlar.add(m().metin("siralama-bos", "&7Henüz kayıtlı dövüşçü yok."));
        for (int i = 0; i < Math.min(10, liste.size()); i++) {
            Dovuscu d = liste.get(i);
            satirlar.add(m().metin("tablo-satir", "&e#{sira} &f{lakap} &8- {lig} &8- &f{puan}",
                    "sira", i + 1, "lakap", d.lakap, "lig", plugin.lig().lig(d).gorunen(), "puan", d.puan));
        }
        return Mesaj.renk(String.join("\n", satirlar));
    }

    private void yaz(TextDisplay t) {
        t.text(LegacyComponentSerializer.legacySection().deserialize(metin()));
    }

    /** Tabloyu günceller; varlık kaybolduysa (ve konum yüklüyse) yeniden oluşturur. */
    public void guncelle() {
        if (konum == null) return;
        Location l = Konum.oku(konum);
        // Chunk'ın varlıkları yüklenmeden aranırsa tablo yok sanılıp ikincisi yaratılırdı
        if (l == null || !l.isChunkLoaded() || !l.getChunk().isEntitiesLoaded()) return;
        Entity e = varlik == null ? null : Bukkit.getEntity(varlik);
        if (e instanceof TextDisplay t) { yaz(t); return; }
        // Varlık bulunamadı: aynı yerde işaretli eski bir tablo varsa onu kullan, yoksa yeniden oluştur
        for (Entity x : l.getWorld().getNearbyEntities(l, 1, 1, 1)) {
            if (x instanceof TextDisplay t && x.getPersistentDataContainer().has(tabloKey, PersistentDataType.BYTE)) {
                varlik = t.getUniqueId();
                dosya.kaydet();
                yaz(t);
                return;
            }
        }
        olusturVarlik(l);
        dosya.kaydet();
    }

    /** Tablonun bulunduğu chunk'ın varlıkları yüklenince güncellenir. */
    @EventHandler
    public void onVarliklar(EntitiesLoadEvent event) {
        if (konum == null) return;
        Location l = Konum.oku(konum);
        if (l != null && l.getWorld().equals(event.getWorld()) && (l.getBlockX() >> 4) == event.getChunk().getX() && (l.getBlockZ() >> 4) == event.getChunk().getZ()) {
            Bukkit.getScheduler().runTask(plugin, this::guncelle);
        }
    }
}
