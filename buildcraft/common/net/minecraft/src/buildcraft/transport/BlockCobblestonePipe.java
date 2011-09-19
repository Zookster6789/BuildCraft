package net.minecraft.src.buildcraft.transport;

import net.minecraft.src.BuildCraftTransport;
import net.minecraft.src.IBlockAccess;
import net.minecraft.src.Material;
import net.minecraft.src.TileEntity;
import net.minecraft.src.buildcraft.api.APIProxy;

public class BlockCobblestonePipe extends BlockPipe {
	
	
	public BlockCobblestonePipe(int i) {
		super(i, Material.rock);

		blockIndexInTexture = 1 * 16 + 1;
	}
	
	@Override
	public TileEntity getBlockEntity() {
		return new TileCobblestonePipe ();
	}
	
	public boolean isPipeConnected(IBlockAccess blockAccess, int x, int y, int z) {
		TileEntity tile = APIProxy.getWorld().getBlockTileEntity(x, y, z);

		if (BuildCraftTransport.alwaysConnectPipes) {
			return super.isPipeConnected(blockAccess, x, y, z);
		} else {
			return !(tile instanceof TileStonePipe)
			&& super.isPipeConnected(blockAccess, x, y, z);
		}
	}
	
}
