package atomicstryker.dynamiclights.client.modules;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import atomicstryker.dynamiclights.client.DynamicLights;
import atomicstryker.dynamiclights.client.IDynamicLightSource;
import atomicstryker.dynamiclights.client.ItemConfigHelper;
import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 
 * @author AtomicStryker
 *
 * Offers Dynamic Light functionality to Player Entities that aren't the client.
 * Handheld Items and Armor can give off Light through this Module.
 *
 */
@Mod(modid = "DynamicLights_otherPlayers", name = "Dynamic Lights Other Player Light", version = "1.0.8", dependencies = "required-after:DynamicLights")
public class PlayerOthersLightSource
{
    private Minecraft mcinstance;
    private long nextUpdate;
    private long updateInterval;
    private ArrayList<OtherPlayerAdapter> trackedPlayers;
    private Thread thread;
    private boolean threadRunning;
    
    private ItemConfigHelper itemsMap;
    private Configuration config;
    
    @EventHandler
    public void preInit(FMLPreInitializationEvent evt)
    {
        config = new Configuration(evt.getSuggestedConfigurationFile());        
        FMLCommonHandler.instance().bus().register(this);
    }
    
    @EventHandler
    public void load(FMLInitializationEvent evt)
    {
        mcinstance = FMLClientHandler.instance().getClient();
        nextUpdate = System.currentTimeMillis();
        trackedPlayers = new ArrayList<OtherPlayerAdapter>();
        threadRunning = false;
    }
    
    @EventHandler
    public void modsLoaded(FMLPostInitializationEvent evt)
    {
        config.load();
        
        Property itemsList = config.get(Configuration.CATEGORY_GENERAL, "LightItems", "torch,glowstone=12,glowstone_dust=10,lit_pumpkin,lava_bucket,redstone_torch=10,redstone=10,golden_helmet=14,easycoloredlights:easycoloredlightsCLStone=-1");
        itemsList.comment = "Item IDs that shine light while held. Armor Items also work when worn. [ONLY ON OTHERS] Syntax: ItemID[-MetaValue]:LightValue, seperated by commas";
        itemsMap = new ItemConfigHelper(itemsList.getString(), 15);
        
        Property updateI = config.get(Configuration.CATEGORY_GENERAL, "update Interval", 1000);
        updateI.comment = "Update Interval time for all other player entities in milliseconds. The lower the better and costlier.";
        updateInterval = updateI.getInt();
        
        config.save();
    }
    
    @SuppressWarnings("unchecked")
    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent tick)
    {
        if (mcinstance.theWorld != null && System.currentTimeMillis() > nextUpdate && !DynamicLights.globalLightsOff())
        {
            nextUpdate = System.currentTimeMillis() + updateInterval;
            
            if (!threadRunning)
            {
                thread = new OtherPlayerChecker(mcinstance.theWorld.loadedEntityList);
                thread.setPriority(Thread.MIN_PRIORITY);
                thread.start();
                threadRunning = true;
            }
        }   
    }
    
    private class OtherPlayerChecker extends Thread
    {
        private final Object[] list;
        
        public OtherPlayerChecker(List<Entity> input)
        {
            list = input.toArray();
        }
        
        @Override
        public void run()
        {
            ArrayList<OtherPlayerAdapter> newList = new ArrayList<OtherPlayerAdapter>();
            
            Entity ent;
            for (Object o : list)
            {
                ent = (Entity) o;
                // Loop all loaded Entities, find alive and valid other Player Entities
                if (ent instanceof EntityOtherPlayerMP && ent.isEntityAlive())
                {
                    // now find them in the already tracked player adapters
                    boolean found = false;
                    Iterator<OtherPlayerAdapter> iter = trackedPlayers.iterator();
                    OtherPlayerAdapter adapter = null;
                    while (iter.hasNext())
                    {
                        adapter = iter.next();
                        if (adapter.getAttachmentEntity().equals(ent)) // already tracked!
                        {
                            adapter.onTick(); // execute a tick
                            newList.add(adapter); // put them in the new list
                            found = true;
                            iter.remove(); // remove them from the old
                            break;
                        }
                    }
                    
                    if (!found) // wasnt already tracked
                    {
                        // make new, tick, put in new list
                        adapter = new OtherPlayerAdapter((EntityPlayer) ent);
                        adapter.onTick();
                        newList.add(adapter);
                    }
                }
            }
            // any remaining adapters were not in the loaded entities. The main Dynamic Lights mod will kill them.
            trackedPlayers = newList;
            threadRunning = false;
        }
        
    }
    
    private class OtherPlayerAdapter implements IDynamicLightSource
    {
        
        private EntityPlayer player;
        private int lightLevel;
        private boolean enabled;
        
        public OtherPlayerAdapter(EntityPlayer p)
        {
            lightLevel = 0;
            enabled = false;
            player = p;
        }
        
        /**
         * Since they are IDynamicLightSource instances, they will already receive updates! Why do we need
         * to do this? Because Player Entities can change equipment and we really don't want this method
         * in an onUpdate tick, way too expensive. So we put it in a seperate Thread!
         */
        public void onTick()
        {
            int prevLight = lightLevel;
            
            lightLevel = itemsMap.getLightFromItemStack(player.getCurrentEquippedItem());
            for (ItemStack armor : player.inventory.armorInventory)
            {
                lightLevel = DynamicLights.maxLight(lightLevel, itemsMap.getLightFromItemStack(armor));
            }
            
            if (prevLight != 0 && lightLevel != prevLight)
            {
                lightLevel = 0;
            }
            else
            {                    
                if (player.isBurning())
                {
                    lightLevel = 15;
                }
            }
            
            if (!enabled && lightLevel > 0)
            {
                enableLight();
            }
            else if (enabled && lightLevel < 1)
            {
                disableLight();
            }
        }
        
        private void enableLight()
        {
            DynamicLights.addLightSource(this);
            enabled = true;
        }
        
        private void disableLight()
        {
            DynamicLights.removeLightSource(this);
            enabled = false;
        }
     
        @Override
        public Entity getAttachmentEntity()
        {
            return player;
        }

        @Override
        public int getLightLevel()
        {
            return lightLevel;
        }
    }

}
