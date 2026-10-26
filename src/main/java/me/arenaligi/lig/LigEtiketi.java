package me.arenaligi.lig;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import me.arenaligi.ArenaLigi;
import me.arenaligi.model.Dovuscu;

/**
 * Tag-Manager (TagPlugin) köprüsü: dövüşçünün ligi ismin en arkasında görünür
 * ([Klan] [Meslek] İsim [Aranıyor] [Lig]). TagPlugin'in "/tag setlig" komutunu kullanır;
 * eklenti kurulu değilse ya da config'te kapalıysa hiçbir şey yapmaz.
 *
 * Etiket, oyuncu arena sınırları içindeyken veya aktif bir maçtayken gösterilir; her saniye
 * çalışan bir kontrol bu durumu değerlendirip etiketi açar/kapatır (TagPlugin'in "setlig"i kalıcı
 * uyguladığı için burada "removelig" ile elle kaldırmak gerekiyor).
 */
public class LigEtiketi {

    private final ArenaLigi plugin;
    private final Set<UUID> gorunenler = new HashSet<>(); // şu an etiketi uygulanmış oyuncular

    public LigEtiketi(ArenaLigi plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::gorunurlukKontrol, 20L, 20L);
    }

    private boolean aktif() {
        return plugin.getConfig().getBoolean("etiket.aktif", true) && Bukkit.getPluginManager().isPluginEnabled("TagPlugin");
    }

    private boolean gorunmeliMi(Player p) {
        return plugin.arena().arenadaMi(p.getLocation()) || plugin.mac().macta(p.getUniqueId());
    }

    /** Dövüşçünün lig etiketini, sadece o an arena sınırında/maçtaysa uygular. */
    public void guncelle(Dovuscu d) {
        if (!aktif() || d.isim == null || !d.isim.matches("[A-Za-z0-9_]{1,16}")) return;
        Player p = Bukkit.getPlayer(d.uuid);
        if (p == null || !gorunmeliMi(p)) return;
        uygula(d);
    }

    private void uygula(Dovuscu d) {
        LigSistemi.Lig lig = plugin.lig().lig(d);
        String format = plugin.getConfig().getString("etiket.format", "&8[{renk}{lig}&8]")
                .replace("{renk}", lig.renk()).replace("{lig}", lig.ad());
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag setlig " + d.isim + " " + format);
        gorunenler.add(d.uuid);
    }

    private void kaldir(Dovuscu d) {
        if (!gorunenler.remove(d.uuid)) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag removelig " + d.isim);
    }

    /** Her saniye: arena sınırı/maç durumu değişen çevrimiçi dövüşçülerin etiketini açar/kapatır. */
    private void gorunurlukKontrol() {
        if (!aktif()) return;
        for (Dovuscu d : plugin.dovusculer().hepsi()) {
            Player p = Bukkit.getPlayer(d.uuid);
            if (p == null || !p.isOnline()) { kaldir(d); continue; }
            if (gorunmeliMi(p)) { if (!gorunenler.contains(d.uuid)) uygula(d); } else kaldir(d); // girişte bir kere uygula; her saniye tekrar atama
        }
    }

    /** Tüm dövüşçüleri (o an arenada/maçta olanları) yeniden değerlendirir. Uygulanan sayı. */
    public int hepsiniGuncelle() {
        if (!aktif()) return 0;
        int n = 0;
        for (Dovuscu d : plugin.dovusculer().hepsi()) {
            Player p = Bukkit.getPlayer(d.uuid);
            if (p != null && gorunmeliMi(p)) { uygula(d); n++; }
        }
        return n;
    }
}
