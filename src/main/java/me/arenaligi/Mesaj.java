package me.arenaligi;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

/**
 * Oyuncu mesajları config.yml'deki "mesajlar" bölümünden okunur (& renk kodları desteklenir).
 * Config'te anahtar yoksa koddaki varsayılan metin kullanılır. {isim} biçimindeki yer tutucular doldurulur.
 */
public class Mesaj {

    private final ArenaLigi plugin;

    public Mesaj(ArenaLigi plugin) {
        this.plugin = plugin;
    }

    public static String renk(String metin) {
        return ChatColor.translateAlternateColorCodes('&', metin);
    }

    public String onek() {
        return renk(plugin.getConfig().getString("mesajlar.onek", "&8[&cArena&8] &r"));
    }

    /** Ham (öneksiz) renkli metin. yerTutucular: "anahtar", değer, "anahtar2", değer2 ... */
    public String metin(String anahtar, String varsayilan, Object... yerTutucular) {
        String m = plugin.getConfig().getString("mesajlar." + anahtar, varsayilan);
        for (int i = 0; i + 1 < yerTutucular.length; i += 2) {
            m = m.replace("{" + yerTutucular[i] + "}", String.valueOf(yerTutucular[i + 1]));
        }
        return renk(m);
    }

    public void gonder(CommandSender alici, String anahtar, String varsayilan, Object... yerTutucular) {
        if (alici == null) return;
        alici.sendMessage(onek() + metin(anahtar, varsayilan, yerTutucular));
    }
}
