package me.ladypaladra.thearmorymod.alteration;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;

/**
 * Persisted chunk store component that tracks the alteration charges remaining
 * on a single Alteration Table block. Modeled on MekanichalKrafterBlock. The
 * component holds state only, callers mutate it and mark the block entity for
 * saving after each change.
 */
public class AlterationTableBlock implements Component<ChunkStore> {

    @Nonnull
    public static final BuilderCodec<AlterationTableBlock> CODEC;

    private int charges = AlterationConfig.INITIAL_CHARGES;

    // Spare kits banked on the table. The live gauge above burns down through charges,
    // and when it hits zero a stocked kit is spent to refill it. This lets a table hold
    // far more than one kit's worth of fuel without ever showing more than three pips.
    private int fuelStock = 0;

    public AlterationTableBlock() {
    }

    public AlterationTableBlock(int charges) {
        this.charges = charges;
    }

    public AlterationTableBlock(int charges, int fuelStock) {
        this.charges = charges;
        this.fuelStock = fuelStock;
    }

    public static ComponentType<ChunkStore, AlterationTableBlock> getComponentType() {
        return AlterationRegistry.getComponentType();
    }

    public int getCharges() {
        return charges;
    }

    public void setCharges(int charges) {
        this.charges = Math.max(0, charges);
    }

    public void addCharges(int amount) {
        this.charges = Math.max(0, this.charges + amount);
    }

    public void consumeCharges(int amount) {
        this.charges = Math.max(0, this.charges - amount);
    }

    public int getFuelStock() {
        return fuelStock;
    }

    public void setFuelStock(int fuelStock) {
        this.fuelStock = Math.max(0, Math.min(fuelStock, AlterationConfig.FUEL_STOCK_CAP));
    }

    /**
     * Banks kits into the stock, clamped to the cap. Returns how many were actually
     * stored so the caller can refuse or partially accept a deposit.
     */
    public int addFuelStock(int amount) {
        if (amount <= 0) {
            return 0;
        }

        int room = AlterationConfig.FUEL_STOCK_CAP - fuelStock;
        int stored = Math.max(0, Math.min(amount, room));
        fuelStock += stored;
        return stored;
    }

    /**
     * Migrates a table saved under the old nine charge gauge onto the new three charge
     * gauge plus stock. Every full kit's worth of surplus charge above the new cap
     * becomes one stocked kit, so nothing a player already paid for is lost, then both
     * values are clamped. Idempotent, so it is safe to call on every resolve.
     */
    public void normalize() {
        while (charges > AlterationConfig.MAX_CHARGES && fuelStock < AlterationConfig.FUEL_STOCK_CAP) {
            charges -= AlterationConfig.CHARGES_PER_FUEL;
            fuelStock++;
        }

        this.charges = Math.max(0, Math.min(charges, AlterationConfig.MAX_CHARGES));
        this.fuelStock = Math.max(0, Math.min(fuelStock, AlterationConfig.FUEL_STOCK_CAP));
    }

    @Nonnull
    @Override
    public Component<ChunkStore> clone() {
        return new AlterationTableBlock(this.charges, this.fuelStock);
    }

    static {
        CODEC = BuilderCodec.builder(AlterationTableBlock.class, AlterationTableBlock::new)
                .appendInherited(
                        new KeyedCodec<>("Charges", Codec.INTEGER),
                        (component, value) -> component.charges = value,
                        component -> component.charges,
                        (component, parent) -> component.charges = parent.charges
                )
                .add()
                .appendInherited(
                        new KeyedCodec<>("FuelStock", Codec.INTEGER),
                        (component, value) -> component.fuelStock = value,
                        component -> component.fuelStock,
                        (component, parent) -> component.fuelStock = parent.fuelStock
                )
                .add()
                .build();
    }
}
