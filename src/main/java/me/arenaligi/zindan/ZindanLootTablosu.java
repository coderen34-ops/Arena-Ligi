package me.arenaligi.zindan;

import java.util.Random;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Zindan katı sandıklarını kat kademesine (tier) göre doldurur - Klan-Sistemi'ndeki Vahşi Kale
 * LootTablosu'nun aynı deseni (tier arttıkça nadir item ihtimali artar), bu sefer "zindan" temalı:
 * barut/kemik/çürük et/ok gibi hammaddeler ağırlıklı, derin katlarda nadir büyü/zırh/totem.
 */
public final class ZindanLootTablosu {

    private ZindanLootTablosu() { }

    /** Mob ölümünde ekstra bonus item düşsün diye (sandık doldurmanın dışında) tek bir rastgele item. */
    public static ItemStack tekRastgeleItem(int tier, Random rng) {
        return rastgeleItem(tier, rng);
    }

    public static void doldur(Inventory envanter, int tier, Random rng) {
        int yuvaSayisi = 3 + rng.nextInt(3) + tier;
        for (int i = 0; i < yuvaSayisi; i++) {
            int yuva = rng.nextInt(envanter.getSize());
            if (envanter.getItem(yuva) != null) continue;
            envanter.setItem(yuva, rastgeleItem(tier, rng));
        }
        if (rng.nextDouble() < 0.008 + tier * 0.004) {
            int bos = ilkBosYuva(envanter, rng);
            if (bos >= 0) envanter.setItem(bos, efsaneviBuyuluKitap(rng));
        }
    }

    private static int ilkBosYuva(Inventory envanter, Random rng) {
        int baslangic = rng.nextInt(envanter.getSize());
        for (int i = 0; i < envanter.getSize(); i++) {
            int yuva = (baslangic + i) % envanter.getSize();
            if (envanter.getItem(yuva) == null) return yuva;
        }
        return -1;
    }

    private static ItemStack rastgeleItem(int tier, Random rng) {
        double zar = rng.nextDouble();
        double nadirEsik = 0.05 + tier * 0.035;
        double iyiEsik = 0.35 + tier * 0.05;
        if (zar < nadirEsik) return nadirItem(tier, rng);
        if (zar < iyiEsik) return iyiItem(tier, rng);
        return hammadde(rng);
    }

    private static ItemStack hammadde(Random rng) {
        Material[] secenekler = {Material.GUNPOWDER, Material.BONE, Material.ROTTEN_FLESH, Material.ARROW,
                Material.IRON_INGOT, Material.COAL, Material.STRING, Material.SPIDER_EYE, Material.TORCH};
        Material m = secenekler[rng.nextInt(secenekler.length)];
        int miktar = switch (m) {
            case ARROW -> 8 + rng.nextInt(16);
            case TORCH -> 4 + rng.nextInt(8);
            case GUNPOWDER, BONE, ROTTEN_FLESH -> 3 + rng.nextInt(6);
            default -> 1 + rng.nextInt(4);
        };
        return new ItemStack(m, miktar);
    }

    private static ItemStack iyiItem(int tier, Random rng) {
        int secim = rng.nextInt(9);
        if (secim == 0) return rastgeleIksir(tier, rng);
        if (secim == 1) return new ItemStack(Material.GOLDEN_APPLE, 1 + rng.nextInt(2));
        if (secim == 2) return rastgeleZirhParcasi(rng.nextBoolean(), rng, tier);
        Material[] secenekler = {Material.GOLD_INGOT, Material.IRON_INGOT, Material.EMERALD,
                Material.EXPERIENCE_BOTTLE, Material.ARROW, Material.SPECTRAL_ARROW, Material.NAME_TAG};
        Material m = secenekler[rng.nextInt(secenekler.length)];
        int miktar = (m == Material.ARROW || m == Material.SPECTRAL_ARROW) ? 8 + rng.nextInt(12) : 1 + rng.nextInt(tier + 2);
        return new ItemStack(m, miktar);
    }

    private static ItemStack nadirItem(int tier, Random rng) {
        if (tier >= 4 && rng.nextDouble() < 0.18) {
            return new ItemStack(Material.TOTEM_OF_UNDYING, 1);
        }
        double zar = rng.nextDouble();
        if (zar < 0.15) return new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 1);
        if (zar < 0.35) return rastgeleZirhParcasi(true, rng, tier);
        if (zar < 0.55) return rastgeleIksir(tier, rng);
        if (zar < 0.75) return new ItemStack(Material.DIAMOND, 1 + rng.nextInt(tier));
        return rastgeleBuyuluKitap(tier, rng);
    }

    private static ItemStack efsaneviBuyuluKitap(Random rng) {
        Enchantment[] havuz = {Enchantment.SHARPNESS, Enchantment.PROTECTION, Enchantment.POWER};
        return buyuluKitapYap(havuz[rng.nextInt(havuz.length)], 6 + rng.nextInt(2));
    }

    private static ItemStack rastgeleBuyuluKitap(int tier, Random rng) {
        Enchantment[] havuz = {Enchantment.SHARPNESS, Enchantment.PROTECTION, Enchantment.UNBREAKING,
                Enchantment.FORTUNE, Enchantment.POWER, Enchantment.MENDING};
        return buyuluKitapYap(havuz[rng.nextInt(havuz.length)], 1 + rng.nextInt(Math.min(3, Math.max(1, tier))));
    }

    private static ItemStack buyuluKitapYap(Enchantment buyu, int seviyeBuyu) {
        ItemStack kitap = new ItemStack(Material.ENCHANTED_BOOK, 1);
        if (kitap.getItemMeta() instanceof EnchantmentStorageMeta meta) {
            meta.addStoredEnchant(buyu, seviyeBuyu, true);
            kitap.setItemMeta(meta);
        }
        return kitap;
    }

    private static ItemStack rastgeleIksir(int tier, Random rng) {
        ItemStack iksir = new ItemStack(Material.POTION, 1);
        ItemMeta meta = iksir.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.PotionMeta pm) {
            org.bukkit.potion.PotionEffectType[] havuz = {
                    org.bukkit.potion.PotionEffectType.STRENGTH, org.bukkit.potion.PotionEffectType.SPEED,
                    org.bukkit.potion.PotionEffectType.REGENERATION, org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE,
                    org.bukkit.potion.PotionEffectType.HEALTH_BOOST, org.bukkit.potion.PotionEffectType.ABSORPTION
            };
            org.bukkit.potion.PotionEffectType efekt = havuz[rng.nextInt(havuz.length)];
            int sureTick = (60 + rng.nextInt(120)) * 20;
            int amplifier = tier >= 4 && rng.nextBoolean() ? 1 : 0;
            pm.addCustomEffect(new org.bukkit.potion.PotionEffect(efekt, sureTick, amplifier), true);
            iksir.setItemMeta(pm);
        }
        return iksir;
    }

    private static ItemStack rastgeleZirhParcasi(boolean enchantli, Random rng, int tier) {
        boolean demir = rng.nextBoolean();
        Material[] demirSet = {Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS};
        Material[] altinSet = {Material.GOLDEN_HELMET, Material.GOLDEN_CHESTPLATE, Material.GOLDEN_LEGGINGS, Material.GOLDEN_BOOTS};
        Material parca = (demir ? demirSet : altinSet)[rng.nextInt(4)];
        ItemStack esya = new ItemStack(parca, 1);
        if (enchantli) {
            ItemMeta meta = esya.getItemMeta();
            if (meta != null) {
                meta.addEnchant(Enchantment.PROTECTION, 1 + rng.nextInt(Math.min(4, Math.max(1, tier))), true);
                if (rng.nextBoolean()) meta.addEnchant(Enchantment.UNBREAKING, 1 + rng.nextInt(2), true);
                esya.setItemMeta(meta);
            }
        }
        return esya;
    }
}
