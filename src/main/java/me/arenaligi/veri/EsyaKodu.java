package me.arenaligi.veri;

import java.util.Base64;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Eşya dizilerini Paper'ın sürüm uyumlu ikili biçimiyle (büyüler, PDC, isimler dahil) Base64 metne çevirir.
 * Boş slotlar korunur (null <-> boş eşya), dizinin uzunluğu ve slot sırası aynen kalır.
 */
public final class EsyaKodu {

    private EsyaKodu() {}

    public static String yaz(ItemStack[] esyalar) {
        ItemStack[] dolu = new ItemStack[esyalar.length];
        for (int i = 0; i < esyalar.length; i++) {
            ItemStack e = esyalar[i];
            dolu[i] = (e == null || e.getType() == Material.AIR) ? ItemStack.empty() : e;
        }
        return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(dolu));
    }

    public static ItemStack[] oku(String metin) {
        ItemStack[] esyalar = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(metin));
        for (int i = 0; i < esyalar.length; i++) {
            if (esyalar[i] != null && (esyalar[i].getType() == Material.AIR || esyalar[i].getAmount() <= 0)) esyalar[i] = null;
        }
        return esyalar;
    }
}
