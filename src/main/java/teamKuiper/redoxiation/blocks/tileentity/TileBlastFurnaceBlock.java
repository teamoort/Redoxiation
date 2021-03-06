package teamKuiper.redoxiation.blocks.tileentity;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MathHelper;
import teamKuiper.redoxiation.blocks.RedoxiationBlocks;
import teamKuiper.redoxiation.items.RedoxiationGenericItems;

import java.util.Arrays;

public class TileBlastFurnaceBlock extends TileEntity implements IInventory {
	private boolean hasMaster, isMaster;
	public boolean hasmastercheck;
	private int masterX, masterY, masterZ;

	@Override
	public void updateEntity() {
		if (!worldObj.isRemote) {
			if (hasMaster()) {
				hasmastercheck = true;
				if (isMaster()) {
					if (!checkMultiBlockForm()) {
						resetStructure();
					}
				}
			} else {
				// Constantly check if structure is formed until it is.
				if (checkMultiBlockForm()) {
					setupStructure();
				}
			}
		}
		if (canSmelt()) {
			int numberOfFuelBurning = burnFuel();

			// If fuel is available, keep cooking the item, otherwise start
			// "uncooking" it at double speed
			if (numberOfFuelBurning > 0) {
				cookTime += numberOfFuelBurning;
			} else {
				cookTime -= 1;
			}

			if (cookTime < 0) {
				cookTime = 0;
			}

			// If cookTime has reached maxCookTime smelt the item and reset
			// cookTime
			if (cookTime >= COOK_TIME_FOR_COMPLETION) {
				smeltItem();
				cookTime = 0;
			}
		} else {
			burnTimeRemaining[0] = 0;
			cookTime = 0;
		}

		// when the number of burning slots changes, we need to force the block
		// to re-render, otherwise the change in
		// state will not be visible. Likewise, we need to force a lighting
		// recalculation.
		// The block update (for renderer) is only required on client side, but
		// the lighting is required on both, since
		// the client needs it for rendering and the server needs it for crop
		// growth etc
		int numberBurning = numberOfBurningFuelSlots();
		if (cachedNumberOfBurningSlots != numberBurning) {
			cachedNumberOfBurningSlots = numberBurning;
			if (worldObj.isRemote) {
				worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
			}
		}
	}

	/** Check that structure is properly formed */
	public boolean checkMultiBlockForm() {
		int i = 0;
		// Scan a 3x3x3 area, starting with the bottom left corner
		for (int x = xCoord - 1; x < xCoord + 2; x++)
			for (int y = yCoord; y < yCoord + 3; y++)
				for (int z = zCoord - 1; z < zCoord + 2; z++) {
					TileEntity tile = worldObj.getTileEntity(x, y, z);
					// Make sure tile isn't null, is an instance of the same
					// Tile, and isn't already a part of a multiblock
					if (tile != null && (tile instanceof TileBlastFurnaceBlock)) {
						if (this.isMaster()) {
							if (((TileBlastFurnaceBlock) tile).hasMaster())
								i++;
						} else if (!((TileBlastFurnaceBlock) tile).hasMaster())
							i++;
					}
				}
		// check if there are 26 blocks present ((3*3*3) - 1) and check that
		// center block is empty
		return i > 25 && worldObj.isAirBlock(xCoord, yCoord + 1, zCoord);
	}

	/** Setup all the blocks in the structure */
	public void setupStructure() {
		for (int x = xCoord - 1; x < xCoord + 2; x++)
			for (int y = yCoord; y < yCoord + 3; y++)
				for (int z = zCoord - 1; z < zCoord + 2; z++) {
					TileEntity tile = worldObj.getTileEntity(x, y, z);
					// Check if block is bottom center block
					boolean master = (x == xCoord && y == yCoord && z == zCoord);
					if (tile != null && (tile instanceof TileBlastFurnaceBlock)) {
						((TileBlastFurnaceBlock) tile).setMasterCoords(xCoord,
								yCoord, zCoord);
						((TileBlastFurnaceBlock) tile).setHasMaster(true);
						((TileBlastFurnaceBlock) tile).setIsMaster(master);
						((TileBlastFurnaceBlock) tile).hasmastercheck = true;
					}
				}
	}

	/** Reset method to be run when the master is gone or tells them to */
	public void reset() {
		masterX = 0;
		masterY = 0;
		masterZ = 0;
		hasMaster = false;
		isMaster = false;
		hasmastercheck = false;
	}

	/** Check that the master exists */
	public boolean checkForMaster() {
		TileEntity tile = worldObj.getTileEntity(masterX, masterY, masterZ);
		return (tile != null && (tile instanceof TileBlastFurnaceBlock));
	}

	/** Reset all the parts of the structure */
	public void resetStructure() {
		for (int x = xCoord - 1; x < xCoord + 2; x++) {
			for (int y = yCoord; y < yCoord + 3; y++) {
				for (int z = zCoord - 1; z < zCoord + 2; z++) {
					TileEntity tile = worldObj.getTileEntity(x, y, z);
					if (tile != null && (tile instanceof TileBlastFurnaceBlock)) {
						((TileBlastFurnaceBlock) tile).reset();
					}
				}
			}
		}
	}

	@Override
	public void writeToNBT(NBTTagCompound data) {
		super.writeToNBT(data);
		data.setInteger("masterX", masterX);
		data.setInteger("masterY", masterY);
		data.setInteger("masterZ", masterZ);
		data.setBoolean("hasMaster", hasMaster);
		data.setBoolean("isMaster", isMaster);
		if (hasMaster() && isMaster()) {
			// Any other values should ONLY BE SAVED TO THE MASTER
		}
		// Save the stored item stacks

		// to use an analogy with Java, this code generates an array of hashmaps
		// The itemStack in each slot is converted to an NBTTagCompound, which
		// is effectively a hashmap of key->value pairs such
		// as slot=1, id=2353, count=1, etc
		// Each of these NBTTagCompound are then inserted into NBTTagList, which
		// is similar to an array.
		NBTTagList dataForAllSlots = new NBTTagList();
		for (int i = 0; i < this.itemStacks.length; ++i) {
			if (this.itemStacks[i] != null) {
				NBTTagCompound dataForThisSlot = new NBTTagCompound();
				dataForThisSlot.setByte("Slot", (byte) i);
				this.itemStacks[i].writeToNBT(dataForThisSlot);
				dataForAllSlots.appendTag(dataForThisSlot);
			}
		}
		// the array of hashmaps is then inserted into the parent hashmap for
		// the container
		data.setTag("Items", dataForAllSlots);

		// Save everything else
		data.setShort("CookTime", cookTime);
		data.setTag("burnTimeRemaining", new NBTTagIntArray(burnTimeRemaining));
		data.setTag("burnTimeInitial", new NBTTagIntArray(burnTimeInitialValue));
	}

	@Override
	public void readFromNBT(NBTTagCompound data) {
		super.readFromNBT(data);
		masterX = data.getInteger("masterX");
		masterY = data.getInteger("masterY");
		masterZ = data.getInteger("masterZ");
		hasMaster = data.getBoolean("hasMaster");
		isMaster = data.getBoolean("isMaster");
		if (hasMaster() && isMaster()) {
			// Any other values should ONLY BE READ BY THE MASTER
		}
		final byte NBT_TYPE_COMPOUND = 10; // See NBTBase.createNewByType() for
											// a listing
		NBTTagList dataForAllSlots = data
				.getTagList("Items", NBT_TYPE_COMPOUND);

		Arrays.fill(itemStacks, null); // set all slots to empty
		for (int i = 0; i < dataForAllSlots.tagCount(); ++i) {
			NBTTagCompound dataForOneSlot = dataForAllSlots.getCompoundTagAt(i);
			byte slotNumber = dataForOneSlot.getByte("Slot");
			if (slotNumber >= 0 && slotNumber < this.itemStacks.length) {
				this.itemStacks[slotNumber] = ItemStack
						.loadItemStackFromNBT(dataForOneSlot);
			}
		}

		// Load everything else. Trim the arrays (or pad with 0) to make sure
		// they have the correct number of elements
		cookTime = data.getShort("CookTime");
		burnTimeRemaining = Arrays.copyOf(
				data.getIntArray("burnTimeRemaining"), FUEL_SLOTS_COUNT);
		burnTimeInitialValue = Arrays.copyOf(
				data.getIntArray("burnTimeInitial"), FUEL_SLOTS_COUNT);
		cachedNumberOfBurningSlots = -1;
	}

	public boolean hasMaster() {
		return hasMaster;
	}

	public boolean isMaster() {
		return isMaster;
	}

	public int getMasterX() {
		return masterX;
	}

	public int getMasterY() {
		return masterY;
	}

	public int getMasterZ() {
		return masterZ;
	}

	public void setHasMaster(boolean bool) {
		hasMaster = bool;
	}

	public void setIsMaster(boolean bool) {
		isMaster = bool;
	}

	public void setMasterCoords(int x, int y, int z) {
		masterX = x;
		masterY = y;
		masterZ = z;
	}

	// Create and initialize the itemStacks variable that will store store the
	// itemStacks
	public static final int FUEL_SLOTS_COUNT = 1;
	public static final int INPUT_SLOTS_COUNT = 3;
	public static final int OUTPUT_SLOTS_COUNT = 2;
	public static final int TOTAL_SLOTS_COUNT = FUEL_SLOTS_COUNT
			+ INPUT_SLOTS_COUNT + OUTPUT_SLOTS_COUNT;

	public static final int FIRST_FUEL_SLOT = 0;
	public static final int FIRST_INPUT_SLOT = FIRST_FUEL_SLOT
			+ FUEL_SLOTS_COUNT;
	public static final int FIRST_OUTPUT_SLOT = FIRST_INPUT_SLOT
			+ INPUT_SLOTS_COUNT;

	private ItemStack[] itemStacks = new ItemStack[TOTAL_SLOTS_COUNT];

	/** The number of burn ticks remaining on the current piece of fuel */
	private int[] burnTimeRemaining = new int[FUEL_SLOTS_COUNT];
	/**
	 * The initial fuel value of the currently burning fuel (in ticks of burn
	 * duration)
	 */
	private int[] burnTimeInitialValue = new int[FUEL_SLOTS_COUNT];

	/** The number of ticks the current item has been cooking */
	private short cookTime;
	/** The number of ticks required to cook an item */
	private static final short COOK_TIME_FOR_COMPLETION = 200; // vanilla value
																// is 200 = 10
																// seconds

	private int cachedNumberOfBurningSlots = -1;

	/**
	 * Returns the amount of fuel remaining on the currently burning item in the
	 * given fuel slot.
	 * 
	 * @param fuelSlot
	 *            the number of the fuel slot (0..3)
	 * @return fraction remaining, between 0 - 1
	 */
	public double fractionOfFuelRemaining(int fuelSlot) {
		if (burnTimeInitialValue[fuelSlot] <= 0)
			return 0;
		double fraction = burnTimeRemaining[fuelSlot]
				/ (double) burnTimeInitialValue[fuelSlot];
		return MathHelper.clamp_double(fraction, 0.0, 1.0);
	}

	/**
	 * return the remaining burn time of the fuel in the given slot
	 * 
	 * @param fuelSlot
	 *            the number of the fuel slot (0..3)
	 * @return seconds remaining
	 */
	public int secondsOfFuelRemaining(int fuelSlot) {
		if (burnTimeRemaining[fuelSlot] <= 0)
			return 0;
		return burnTimeRemaining[fuelSlot] / 20; // 20 ticks per second
	}

	/**
	 * Get the number of slots which have fuel burning in them.
	 * 
	 * @return number of slots with burning fuel, 0 - FUEL_SLOTS_COUNT
	 */
	public int numberOfBurningFuelSlots() {
		int burningCount = 0;
		for (int burnTime : burnTimeRemaining) {
			if (burnTime > 0)
				++burningCount;
		}
		return burningCount;
	}

	/**
	 * Returns the amount of cook time completed on the currently cooking item.
	 * 
	 * @return fraction remaining, between 0 - 1
	 */
	public double fractionOfCookTimeComplete() {
		double fraction = cookTime / (double) COOK_TIME_FOR_COMPLETION;
		return MathHelper.clamp_double(fraction, 0.0, 1.0);
	}

	/**
	 * for each fuel slot: decreases the burn time, checks if burnTimeRemaining
	 * = 0 and tries to consume a new piece of fuel if one is available
	 * 
	 * @return the number of fuel slots which are burning
	 */
	private int burnFuel() {
		int burningCount = 0;
		boolean inventoryChanged = false;
		// Iterate over all the fuel slots
		for (int i = 0; i < FUEL_SLOTS_COUNT; i++) {
			int fuelSlotNumber = i + FIRST_FUEL_SLOT;
			if (burnTimeRemaining[i] > 0) {
				--burnTimeRemaining[i];
				++burningCount;
			}
			if (burnTimeRemaining[i] == 0) {
				if (itemStacks[fuelSlotNumber] != null
						&& getItemBurnTime(itemStacks[fuelSlotNumber]) > 0) {
					// If the stack in this slot is not null and is fuel, set
					// burnTimeRemaining & burnTimeInitialValue to the
					// item's burn time and decrease the stack size
					burnTimeRemaining[i] = burnTimeInitialValue[i] = getItemBurnTime(itemStacks[fuelSlotNumber]);
					--itemStacks[fuelSlotNumber].stackSize;
					++burningCount;
					inventoryChanged = true;
					// If the stack size now equals 0 set the slot contents to
					// the items container item. This is for fuel
					// items such as lava buckets so that the bucket is not
					// consumed. If the item dose not have
					// a container item getContainerItem returns null which sets
					// the slot contents to null
					if (itemStacks[fuelSlotNumber].stackSize == 0) {
						itemStacks[fuelSlotNumber] = itemStacks[fuelSlotNumber]
								.getItem().getContainerItem(
										itemStacks[fuelSlotNumber]);
					}
				}
			}
		}
		if (inventoryChanged)
			markDirty();
		return burningCount;
	}

	/**
	 * Check if any of the input items are smeltable and there is sufficient
	 * space in the output slots
	 * 
	 * @return true if smelting is possible
	 */
	private boolean canSmelt() {
		return smeltItem(false);
	}

	/**
	 * Smelt an input item into an output slot, if possible
	 */
	private void smeltItem() {
		smeltItem(true);
	}

	/**
	 * checks that there is an item to be smelted in one of the input slots and
	 * that there is room for the result in the output slots If desired,
	 * performs the smelt
	 * 
	 * @param performSmelt
	 *            if true, perform the smelt. if false, check whether smelting
	 *            is possible, but don't change the inventory
	 * @return false if no items can be smelted, true otherwise
	 */
	private boolean smeltItem(boolean performSmelt) {
		boolean hasIronOre = false;
		boolean hasCarbon = false;
		boolean hasCalcite = false;

		// finds the first input slot which is smeltable and whose result fits
		// into an output slot (stacking if possible)
		for (int inputSlot = FIRST_INPUT_SLOT; inputSlot < FIRST_INPUT_SLOT
				+ INPUT_SLOTS_COUNT; inputSlot++) {
			if (itemStacks[inputSlot] != null) {
				if (itemStacks[inputSlot].getItem() == Item
						.getItemFromBlock(Blocks.iron_ore)
						&& itemStacks[inputSlot].stackSize >= 4) {
					hasIronOre = true;
				}
				if (itemStacks[inputSlot].getItem() == Items.coal
						&& itemStacks[inputSlot].stackSize >= 17) {
					hasCarbon = true;
				}
				if (itemStacks[inputSlot].getItem() == RedoxiationGenericItems.Calcite
						&& itemStacks[inputSlot].stackSize >= 11) {
					hasCalcite = true;
				}
			}
		}
		if (!(hasIronOre && hasCarbon && hasCalcite)) {
			return false;
		}

		for (int outputSlot = FIRST_OUTPUT_SLOT; outputSlot < FIRST_OUTPUT_SLOT
				+ OUTPUT_SLOTS_COUNT; outputSlot++) {
			if (itemStacks[outputSlot] != null) {
				if (itemStacks[outputSlot].stackSize > getInventoryStackLimit() - 3) {
					return false;
				}
			}
		}

		if (!performSmelt) {
			return true;
		}
		for (int inputSlot = FIRST_INPUT_SLOT; inputSlot < FIRST_INPUT_SLOT
				+ INPUT_SLOTS_COUNT; inputSlot++) {
			if (itemStacks[inputSlot].getItem() == Item
					.getItemFromBlock(Blocks.iron_ore)) {
				itemStacks[inputSlot].stackSize -= 4;
			}
			if (itemStacks[inputSlot].getItem() == Items.coal
					&& itemStacks[inputSlot].stackSize >= 17) {
				itemStacks[inputSlot].stackSize -= 17;
			}
			if (itemStacks[inputSlot].getItem() == RedoxiationGenericItems.Calcite
					&& itemStacks[inputSlot].stackSize >= 11) {
				itemStacks[inputSlot].stackSize -= 11;
			}
			if (itemStacks[inputSlot].stackSize <= 0) {
				itemStacks[inputSlot] = null;
			}
		}
		for (int outputSlot = FIRST_OUTPUT_SLOT; outputSlot < FIRST_OUTPUT_SLOT
				+ OUTPUT_SLOTS_COUNT; outputSlot++) {
			if (itemStacks[outputSlot] == null) {
				if (outputSlot == FIRST_OUTPUT_SLOT) {
					itemStacks[outputSlot] = new ItemStack(
							RedoxiationBlocks.MoltenPigironBlock, 3);
				} else {
					itemStacks[outputSlot] = new ItemStack(
							RedoxiationBlocks.SlagBlock, 3);
				}
			} else {
				if (itemStacks[outputSlot].stackSize > getInventoryStackLimit() - 3) {
					itemStacks[outputSlot].stackSize = getInventoryStackLimit();
				}
				itemStacks[outputSlot].stackSize += 3;
			}
		}
		markDirty();
		return true;
	}

	// returns the smelting result for the given stack. Returns null if the
	// given stack can not be smelted
	public static ItemStack getSmeltingResultForItem(ItemStack stack) {
		Item item = stack.getItem();
		if (item == Item.getItemFromBlock(Blocks.iron_ore)
				|| item == Items.coal
				|| item == RedoxiationGenericItems.Calcite) {
			return new ItemStack(RedoxiationBlocks.MoltenPigironBlock);
		}
		return null;
	}

	// returns the number of ticks the given item will burn. Returns 0 if the
	// given item is not a valid fuel
	public static short getItemBurnTime(ItemStack stack) {
		int burntime = 0;
		if (stack.getItem() == Item
				.getItemFromBlock(RedoxiationBlocks.HotAirBlock)) {
			burntime = 1600;
		}
		return (short) MathHelper.clamp_int(burntime, 0, Short.MAX_VALUE);
	}

	// Gets the number of slots in the inventory
	@Override
	public int getSizeInventory() {
		return itemStacks.length;
	}

	// Gets the stack in the given slot
	@Override
	public ItemStack getStackInSlot(int i) {
		if (i > TOTAL_SLOTS_COUNT) {
			return null;
		}
		return itemStacks[i];
	}

	/**
	 * Removes some of the units from itemstack in the given slot, and returns
	 * as a separate itemstack
	 * 
	 * @param slotIndex
	 *            the slot number to remove the items from
	 * @param count
	 *            the number of units to remove
	 * @return a new itemstack containing the units removed from the slot
	 */
	@Override
	public ItemStack decrStackSize(int slotIndex, int count) {
		ItemStack itemStackInSlot = getStackInSlot(slotIndex);
		if (itemStackInSlot == null)
			return null;

		ItemStack itemStackRemoved;
		if (itemStackInSlot.stackSize <= count) {
			itemStackRemoved = itemStackInSlot;
			setInventorySlotContents(slotIndex, null);
		} else {
			itemStackRemoved = itemStackInSlot.splitStack(count);
			if (itemStackInSlot.stackSize == 0) {
				setInventorySlotContents(slotIndex, null);
			}
		}
		markDirty();
		return itemStackRemoved;
	}

	// overwrites the stack in the given slotIndex with the given stack
	@Override
	public void setInventorySlotContents(int slotIndex, ItemStack itemstack) {
		itemStacks[slotIndex] = itemstack;
		if (itemstack != null && itemstack.stackSize > getInventoryStackLimit()) {
			itemstack.stackSize = getInventoryStackLimit();
		}
		markDirty();
	}

	// This is the maximum number if items allowed in each slot
	// This only affects things such as hoppers trying to insert items you need
	// to use the container to enforce this for players
	// inserting items via the gui
	@Override
	public int getInventoryStackLimit() {
		return 64;
	}

	// Return true if the given player is able to use this block. In this case
	// it checks that
	// 1) the world tileentity hasn't been replaced in the meantime, and
	// 2) the player isn't too far away from the centre of the block
	@Override
	public boolean isUseableByPlayer(EntityPlayer player) {
		if (this.worldObj.getTileEntity(xCoord, yCoord, zCoord) != this)
			return false;
		final double X_CENTRE_OFFSET = 0.5;
		final double Y_CENTRE_OFFSET = 0.5;
		final double Z_CENTRE_OFFSET = 0.5;
		final double MAXIMUM_DISTANCE_SQ = 8.0 * 8.0;
		return player.getDistanceSq(xCoord + X_CENTRE_OFFSET, yCoord
				+ Y_CENTRE_OFFSET, zCoord + Z_CENTRE_OFFSET) < MAXIMUM_DISTANCE_SQ;
	}

	// Return true if the given stack is allowed to be inserted in the given
	// slot
	// Unlike the vanilla furnace, we allow anything to be placed in the fuel
	// slots
	static public boolean isItemValidForFuelSlot(ItemStack itemStack) {
		Item item = itemStack.getItem();
		return item == Item.getItemFromBlock(RedoxiationBlocks.HotAirBlock);
	}

	// Return true if the given stack is allowed to be inserted in the given
	// slot
	// Unlike the vanilla furnace, we allow anything to be placed in the fuel
	// slots
	static public boolean isItemValidForInputSlot(ItemStack itemStack) {
		Item item = itemStack.getItem();
		return item == Item.getItemFromBlock(Blocks.iron_ore)
				|| item == Items.coal
				|| item == RedoxiationGenericItems.Calcite;
	}

	// Return true if the given stack is allowed to be inserted in the given
	// slot
	// Unlike the vanilla furnace, we allow anything to be placed in the fuel
	// slots
	static public boolean isItemValidForOutputSlot(ItemStack itemStack) {
		return false;
	}

	// When the world loads from disk, the server needs to send the TileEntity
	// information to the client
	// it uses getDescriptionPacket() and onDataPacket() to do this
	@Override
	public Packet getDescriptionPacket() {
		NBTTagCompound nbtTagCompound = new NBTTagCompound();
		writeToNBT(nbtTagCompound);
		final int METADATA = 0;
		return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, METADATA,
				nbtTagCompound);
	}

	@Override
	public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
		readFromNBT(pkt.func_148857_g());
	}

	// will add a key for this container to the lang file so we can name it in
	// the GUI
	@Override
	public String getInventoryName() {
		return "BlastFurnace";
	}

	@Override
	public boolean hasCustomInventoryName() {
		return false;
	}

	// standard code to look up what the human-readable name is
	public IChatComponent getDisplayName() {
		return this.hasCustomInventoryName() ? new ChatComponentText(
				this.getInventoryName()) : new ChatComponentTranslation(
				this.getInventoryName());
	}

	// Fields are used to send non-inventory information from the server to
	// interested clients
	// The container code caches the fields and sends the client any fields
	// which have changed.
	// The field ID is limited to byte, and the field value is limited to short.
	// (if you use more than this, they get cast down
	// in the network packets)
	// If you need more than this, or shorts are too small, use a custom packet
	// in your container instead.

	private static final byte COOK_FIELD_ID = 0;
	private static final byte FIRST_BURN_TIME_REMAINING_FIELD_ID = 1;
	private static final byte FIRST_BURN_TIME_INITIAL_FIELD_ID = FIRST_BURN_TIME_REMAINING_FIELD_ID
			+ (byte) FUEL_SLOTS_COUNT;
	private static final byte NUMBER_OF_FIELDS = FIRST_BURN_TIME_INITIAL_FIELD_ID
			+ (byte) FUEL_SLOTS_COUNT;

	public int getField(int id) {
		if (id == COOK_FIELD_ID)
			return cookTime;
		if (id >= FIRST_BURN_TIME_REMAINING_FIELD_ID
				&& id < FIRST_BURN_TIME_REMAINING_FIELD_ID + FUEL_SLOTS_COUNT) {
			return burnTimeRemaining[id - FIRST_BURN_TIME_REMAINING_FIELD_ID];
		}
		if (id >= FIRST_BURN_TIME_INITIAL_FIELD_ID
				&& id < FIRST_BURN_TIME_INITIAL_FIELD_ID + FUEL_SLOTS_COUNT) {
			return burnTimeInitialValue[id - FIRST_BURN_TIME_INITIAL_FIELD_ID];
		}
		System.err
				.println("Invalid field ID in TileInventorySmelting.getField:"
						+ id);
		return 0;
	}

	public void setField(int id, int value) {
		if (id == COOK_FIELD_ID) {
			cookTime = (short) value;
		} else if (id >= FIRST_BURN_TIME_REMAINING_FIELD_ID
				&& id < FIRST_BURN_TIME_REMAINING_FIELD_ID + FUEL_SLOTS_COUNT) {
			burnTimeRemaining[id - FIRST_BURN_TIME_REMAINING_FIELD_ID] = value;
		} else if (id >= FIRST_BURN_TIME_INITIAL_FIELD_ID
				&& id < FIRST_BURN_TIME_INITIAL_FIELD_ID + FUEL_SLOTS_COUNT) {
			burnTimeInitialValue[id - FIRST_BURN_TIME_INITIAL_FIELD_ID] = value;
		} else {
			System.err
					.println("Invalid field ID in TileInventorySmelting.setField:"
							+ id);
		}
	}

	public int getFieldCount() {
		return NUMBER_OF_FIELDS;
	}

	// -----------------------------------------------------------------------------------------------------------
	// The following methods are not needed for this example but are part of
	// IInventory so they must be implemented

	// Unused unless your container specifically uses it.
	// Return true if the given stack is allowed to go in the given slot
	@Override
	public boolean isItemValidForSlot(int slotIndex, ItemStack itemstack) {
		return false;
	}

	/**
	 * This method removes the entire contents of the given slot and returns it.
	 * Used by containers such as crafting tables which return any items in
	 * their slots when you close the GUI
	 */
	@Override
	public ItemStack getStackInSlotOnClosing(int slotIndex) {
		ItemStack itemStack = getStackInSlot(slotIndex);
		if (itemStack != null)
			setInventorySlotContents(slotIndex, null);
		return itemStack;
	}

	@Override
	public void openInventory() {
	}

	@Override
	public void closeInventory() {
	}
}