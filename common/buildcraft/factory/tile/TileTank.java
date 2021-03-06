/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.factory.tile;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;

import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.FluidTankProperties;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import buildcraft.api.core.EnumPipePart;
import buildcraft.api.core.IFluidFilter;
import buildcraft.api.core.IFluidHandlerAdv;
import buildcraft.api.tiles.IDebuggable;

import buildcraft.lib.fluid.FluidSmoother;
import buildcraft.lib.fluid.FluidSmoother.FluidStackInterp;
import buildcraft.lib.fluid.Tank;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.misc.FluidUtilBC;
import buildcraft.lib.misc.data.IdAllocator;
import buildcraft.lib.net.PacketBufferBC;
import buildcraft.lib.tile.TileBC_Neptune;

public class TileTank extends TileBC_Neptune implements ITickable, IDebuggable, IFluidHandlerAdv {
    public static final IdAllocator IDS = TileBC_Neptune.IDS.makeChild("tank");
    public static final int NET_FLUID_DELTA = IDS.allocId("FLUID_DELTA");

    private static boolean isPlayerInteracting = false;

    public final Tank tank;
    public final FluidSmoother smoothedTank;

    private int lastComparatorLevel;

    public TileTank() {
        this(16 * Fluid.BUCKET_VOLUME);
    }

    protected TileTank(int capacity) {
        this(new Tank("tank", capacity, null));
    }

    protected TileTank(Tank tank) {
        tank.setTileEntity(this);
        this.tank = tank;
        tankManager.add(tank);
        caps.addCapabilityInstance(CapUtil.CAP_FLUIDS, this, EnumPipePart.VALUES);
        smoothedTank = new FluidSmoother(w -> createAndSendMessage(NET_FLUID_DELTA, w), tank);
    }

    @Override
    public IdAllocator getIdAllocator() {
        return IDS;
    }

    public int getComparatorLevel() {
        int amount = tank.getFluidAmount();
        int cap = tank.getCapacity();
        return amount * 14 / cap + (amount > 0 ? 1 : 0);
    }

    // ITickable

    @Override
    public void update() {
        smoothedTank.tick(world);

        if (!world.isRemote) {
            int compLevel = getComparatorLevel();
            if (compLevel != lastComparatorLevel) {
                lastComparatorLevel = compLevel;
                markDirty();
            }
        }
    }

    // TileEntity

    @Override
    public void onPlacedBy(EntityLivingBase placer, ItemStack stack) {
        super.onPlacedBy(placer, stack);
        if (!placer.world.isRemote) {
            isPlayerInteracting = true;
            balanceTankFluids();
            isPlayerInteracting = false;
        }
    }

    /** Moves fluids around to their preferred positions. (For gaseous fluids this will move everything as high as
     * possible, for liquid fluids this will move everything as low as possible.) */
    public void balanceTankFluids() {
        List<TileTank> tanks = getTanks();
        FluidStack fluid = null;
        for (TileTank tile : tanks) {
            FluidStack held = tile.tank.getFluid();
            if (held == null) {
                continue;
            }
            if (fluid == null) {
                fluid = held;
            } else if (!fluid.isFluidEqual(held)) {
                return;
            }
        }
        if (fluid == null) {
            return;
        }
        if (fluid.getFluid().isGaseous(fluid)) {
            Collections.reverse(tanks);
        }
        TileTank prev = null;
        for (TileTank tile : tanks) {
            if (prev != null) {
                FluidUtilBC.move(tile.tank, prev.tank);
            }
            prev = tile;
        }
    }

    @Override
    public boolean onActivated(EntityPlayer player, EnumHand hand, EnumFacing facing, float hitX, float hitY,
        float hitZ) {
        isPlayerInteracting = true;
        boolean didChange = FluidUtilBC.onTankActivated(player, pos, hand, this);
        isPlayerInteracting = false;
        return didChange;
    }

    // Networking

    @Override
    public void writePayload(int id, PacketBufferBC buffer, Side side) {
        super.writePayload(id, buffer, side);
        if (side == Side.SERVER) {
            if (id == NET_RENDER_DATA) {
                writePayload(NET_FLUID_DELTA, buffer, side);
            } else if (id == NET_FLUID_DELTA) {
                smoothedTank.writeInit(buffer);
            }
        }
    }

    @Override
    public void readPayload(int id, PacketBufferBC buffer, Side side, MessageContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);
        if (side == Side.CLIENT) {
            if (id == NET_RENDER_DATA) {
                readPayload(NET_FLUID_DELTA, buffer, side, ctx);
                smoothedTank.resetSmoothing(getWorld());
            } else if (id == NET_FLUID_DELTA) {
                smoothedTank.handleMessage(getWorld(), buffer);
            }
        }
    }

    // IDebuggable

    @Override
    public void getDebugInfo(List<String> left, List<String> right, EnumFacing side) {
        left.add("fluid = " + tank.getDebugString());
        smoothedTank.getDebugInfo(left, right, side);
    }

    // Rendering

    @SideOnly(Side.CLIENT)
    public FluidStackInterp getFluidForRender(float partialTicks) {
        return smoothedTank.getFluidForRender(partialTicks);
    }

    // Tank helper methods

    /** Tests to see if this tank can connect to the other one, in the given direction. BuildCraft itself only calls
     * with {@link EnumFacing#UP} or {@link EnumFacing#DOWN}, however addons are free to call with any of the other 4
     * non-null faces. (Although an addon calling from other faces must provide some way of transferring fluids around).
     * 
     * @param other The other tank.
     * @param direction The direction that the other tank is, from this tank.
     * @return True if this can connect, false otherwise. */
    public boolean canConnectTo(TileTank other, EnumFacing direction) {
        return true;
    }

    /** Helper for {@link #canConnectTo(TileTank, EnumFacing)} that only returns true if both tanks can connect to each
     * other.
     * 
     * @param from
     * @param to
     * @param direction The direction from the "from" tank, to the "to" tank, such that
     *            {@link Objects#equals(Object, Object) Objects.equals(}{@link TileTank#getPos()
     *            from.getPos()}.{@link BlockPos#offset(EnumFacing) offset(direction)}, {@link TileTank#getPos()
     *            to.getPos()}) returns true.
     * @return True if both could connect, false otherwise. */
    public static boolean canTanksConnect(TileTank from, TileTank to, EnumFacing direction) {
        return from.canConnectTo(to, direction) && to.canConnectTo(from, direction.getOpposite());
    }

    /** @return A list of all connected tanks around this block, ordered by position from bottom to top. */
    private List<TileTank> getTanks() {
        // double-ended queue rather than array list to avoid
        // the copy operation when we search downwards
        Deque<TileTank> tanks = new ArrayDeque<>();
        tanks.add(this);
        TileTank prevTank = this;
        while (true) {
            TileEntity tileAbove = prevTank.getNeighbourTile(EnumFacing.UP);
            if (!(tileAbove instanceof TileTank)) {
                break;
            }
            TileTank tankUp = (TileTank) tileAbove;
            if (tankUp != null && canTanksConnect(prevTank, tankUp, EnumFacing.UP)) {
                tanks.addLast(tankUp);
            } else {
                break;
            }
            prevTank = tankUp;
        }
        prevTank = this;
        while (true) {
            TileEntity tileBelow = prevTank.getNeighbourTile(EnumFacing.DOWN);
            if (!(tileBelow instanceof TileTank)) {
                break;
            }
            TileTank tankBelow = (TileTank) tileBelow;
            if (tankBelow != null && canTanksConnect(prevTank, tankBelow, EnumFacing.DOWN)) {
                tanks.addFirst(tankBelow);
            } else {
                break;
            }
            prevTank = tankBelow;
        }
        return new ArrayList<>(tanks);
    }

    // IFluidHandler

    @Override
    public IFluidTankProperties[] getTankProperties() {
        List<TileTank> tanks = getTanks();
        TileTank bottom = tanks.get(0);
        FluidStack total = bottom.tank.getFluid();
        int capacity = 0;
        if (total == null) {
            for (TileTank t : tanks) {
                capacity += t.tank.getCapacity();
            }
        } else {
            total = total.copy();
            total.amount = 0;
            for (TileTank t : tanks) {
                FluidStack other = t.tank.getFluid();
                if (other != null) {
                    total.amount += other.amount;
                }
                capacity += t.tank.getCapacity();
            }
        }
        return new IFluidTankProperties[] { new FluidTankProperties(total, capacity) };
    }

    @Override
    public int fill(FluidStack resource, boolean doFill) {
        if (resource == null || resource.amount <= 0) {
            return 0;
        }
        int filled = 0;
        List<TileTank> tanks = getTanks();
        for (TileTank t : tanks) {
            FluidStack current = t.tank.getFluid();
            if (current != null && !current.isFluidEqual(resource)) {
                return 0;
            }
        }
        boolean gas = resource.getFluid().isGaseous(resource);
        if (gas) {
            Collections.reverse(tanks);
        }
        resource = resource.copy();
        for (TileTank t : tanks) {
            int tankFilled = t.tank.fill(resource, doFill);
            if (tankFilled > 0) {
                if (isPlayerInteracting & doFill) {
                    t.sendNetworkUpdate(NET_RENDER_DATA);
                }
                resource.amount -= tankFilled;
                filled += tankFilled;
                if (resource.amount == 0) {
                    break;
                }
            }
        }
        return filled;
    }

    @Override
    public FluidStack drain(int maxDrain, boolean doDrain) {
        return drain((fluid) -> true, maxDrain, doDrain);
    }

    @Override
    public FluidStack drain(FluidStack resource, boolean doDrain) {
        if (resource == null) {
            return null;
        }
        return drain(resource::isFluidEqual, resource.amount, doDrain);
    }

    // IFluidHandlerAdv

    @Override
    public FluidStack drain(IFluidFilter filter, int maxDrain, boolean doDrain) {
        if (maxDrain <= 0) {
            return null;
        }
        List<TileTank> tanks = getTanks();
        boolean gas = false;
        for (TileTank tile : tanks) {
            FluidStack fluid = tile.tank.getFluid();
            if (fluid != null) {
                gas = fluid.getFluid().isGaseous(fluid);
                break;
            }
        }
        if (!gas) {
            Collections.reverse(tanks);
        }
        FluidStack total = null;
        for (TileTank t : tanks) {
            int realMax = maxDrain - (total == null ? 0 : total.amount);
            if (realMax <= 0) {
                break;
            }
            FluidStack drained = t.tank.drain(filter, realMax, doDrain);
            if (drained == null) continue;
            if (isPlayerInteracting & doDrain) {
                t.sendNetworkUpdate(NET_RENDER_DATA);
            }
            if (total == null) {
                total = drained.copy();
                total.amount = 0;
            }
            total.amount += drained.amount;
        }
        return total;
    }
}
