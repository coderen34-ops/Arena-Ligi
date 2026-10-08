package me.arenaligi.lig;

import org.bukkit.Bukkit;

import me.arenaligi.ArenaLigi;
import me.arenaligi.model.Dovuscu;

/**
 * Tag-Manager (TagPlugin) köprüsü: dövüşçünün ligi ismin en arkasında görünür
 * ([Klan] [Meslek] İsim [Aranıyor] [Lig]). TagPlugin'in "/tag setlig" komutunu kullanır;
 * eklenti kurulu değilse ya da config'te kapalıysa hiçbir şey yapmaz.
 */
public class LigEtiketi {

    private final ArenaLigi plugin;

    public LigEtiketi(ArenaLigi plugin) {
        this.plugin = plugin;
    }

    private boolean aktif() {
        return plugin.getConfig().getBoolean("etiket.aktif", true) && Bukkit.getPluginManager().isPluginEnabled("TagPlugin");
    }

    /** Dövüşçünün lig etiketini (yeniden) uygular. */
    public void guncelle(Dovuscu d) {
        if (!aktif() || d.isim == null || !d.isim.matches("[A-Za-z0-9_]{1,16}")) return;
        LigSistemi.Lig lig = plugin.lig().lig(d);
        String format = plugin.getConfig().getString("etiket.format", "&8[{renk}{lig}&8]")
                .replace("{renk}", lig.renk()).replace("{lig}", lig.ad());
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag setlig " + d.isim + " " + format);
    }

    /** Tüm dövüşçülerin etiketini yeniden uygular. Uygulanan sayı. */
    public int hepsiniGuncelle() {
        if (!aktif()) return 0;
        int n = 0;
        for (Dovuscu d : plugin.dovusculer().hepsi()) { guncelle(d); n++; }
        return n;
    }
}
