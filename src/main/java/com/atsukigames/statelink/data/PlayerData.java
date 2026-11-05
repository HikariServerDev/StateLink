package com.atsukigames.statelink.data;

import java.util.UUID;

public class PlayerData implements SyncableData {
    private UUID uuid;
    private String username;
    private String inventory;
    private String enderChest;
    private String armor;
    private String offhand;
    private double health;
    private int foodLevel;
    private float saturation;
    private float exhaustion;
    private int experienceLevel;
    private int experiencePoints;
    private String effects;

    public PlayerData() {}

    public PlayerData(UUID uuid, String username) {
        this.uuid = uuid;
        this.username = username;
    }

    // Getters and Setters
    public UUID getUuid() { return uuid; }
    public void setUuid(UUID uuid) { this.uuid = uuid; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getInventory() { return inventory; }
    public void setInventory(String inventory) { this.inventory = inventory; }

    public String getEnderChest() { return enderChest; }
    public void setEnderChest(String enderChest) { this.enderChest = enderChest; }

    public String getArmor() { return armor; }
    public void setArmor(String armor) { this.armor = armor; }

    public String getOffhand() { return offhand; }
    public void setOffhand(String offhand) { this.offhand = offhand; }

    public double getHealth() { return health; }
    public void setHealth(double health) { this.health = health; }

    public int getFoodLevel() { return foodLevel; }
    public void setFoodLevel(int foodLevel) { this.foodLevel = foodLevel; }

    public float getSaturation() { return saturation; }
    public void setSaturation(float saturation) { this.saturation = saturation; }

    public float getExhaustion() { return exhaustion; }
    public void setExhaustion(float exhaustion) { this.exhaustion = exhaustion; }

    public int getExperienceLevel() { return experienceLevel; }
    public void setExperienceLevel(int experienceLevel) { this.experienceLevel = experienceLevel; }

    public int getExperiencePoints() { return experiencePoints; }
    public void setExperiencePoints(int experiencePoints) { this.experiencePoints = experiencePoints; }

    public String getEffects() { return effects; }
    public void setEffects(String effects) { this.effects = effects; }

    @Override
    public boolean isValidData() {
        return uuid != null && username != null && !username.isEmpty();
    }

    @Override
    public String toString() {
        return "PlayerData{" +
               "uuid=" + uuid +
               ", username='" + username + '\'' +
               ", health=" + health +
               ", foodLevel=" + foodLevel +
               ", experienceLevel=" + experienceLevel +
               '}';
    }
}
