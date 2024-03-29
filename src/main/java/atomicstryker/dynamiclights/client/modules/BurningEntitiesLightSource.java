package atomicstryker.dynamiclights.client.modules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import atomicstryker.dynamiclights.client.DynamicLights;
import atomicstryker.dynamiclights.client.IDynamicLightSource;
import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 
 * @author AtomicStryker
 *
 * Offers Dynamic Light functionality to EntityLiving instances, Fireballs and Arrows on Fire.
 * Burning Entites can give off Light through this Module.
 *
 */
@Mod(modid = "DynamicLights_onFire", name = "Dynamic Lights on burning", version = "1.0.5", dependencies = "required-after:DynamicLights")
public class BurningEntitiesLightSource
{
    private Minecraft mcinstance;
    private long nextUpdate;
    private long updateInterval;
    private ArrayList<EntityLightAdapter> trackedEntities;
    private Thread thread;
    private boolean threadRunning;
    private Configuration config;
    private HashMap<Class<? extends Entity>, Integer> lightValueMap;
    
    @EventHandler
    public void preInit(FMLPreInitializationEvent evt)
    {
        lightValueMap = new HashMap<Class<? extends Entity>, Integer>();
        config = new Configuration(evt.getSuggestedConfigurationFile());
        config.load();
        
        Property updateI = config.get(Configuration.CATEGORY_GENERAL, "update Interval", 1000);
        updateI.comment = "Update Interval time for all burning EntityLiving, Arrows and Fireballs in milliseconds. The lower the better and costlier.";
        updateInterval = updateI.getInt();
        
        config.save();
        
        FMLCommonHandler.instance().bus().register(this);
    }
    
    @EventHandler
    public void load(FMLInitializationEvent evt)
    {
        mcinstance = FMLClientHandler.instance().getClient();
        nextUpdate = System.currentTimeMillis();
        trackedEntities = new ArrayList<EntityLightAdapter>();
        threadRunning = false;
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
                thread = new EntityListChecker(mcinstance.theWorld.loadedEntityList);
                thread.setPriority(Thread.MIN_PRIORITY);
                thread.start();
                threadRunning = true;
            }
        }
    }
    
    private class EntityListChecker extends Thread
    {
        private final Object[] list;
        
        public EntityListChecker(List<Entity> input)
        {
            list = input.toArray();
        }
        
        @Override
        public void run()
        {
            ArrayList<EntityLightAdapter> newList = new ArrayList<EntityLightAdapter>();
            
            Entity ent;
            for (Object o : list)
            {
                ent = (Entity) o;
                // Loop all loaded Entities, find alive and valid EntityLiving not otherwise handled
                if ((ent instanceof EntityLivingBase || ent instanceof EntityFireball || ent instanceof EntityArrow)
                        && ent.isEntityAlive() && ent.isBurning() && !(ent instanceof EntityItem) && !(ent instanceof EntityPlayer))
                {
                    boolean shouldLight = false;
                    if (!lightValueMap.containsKey(ent.getClass()))
                    {
                        config.load();
                        int value = config.get(Configuration.CATEGORY_GENERAL, ent.getClass().getSimpleName(), 1, "Set to 0 if you don't want that entclass to shine light when on fire").getInt();
                        config.save();
                        
                        lightValueMap.put(ent.getClass(), value);
                        shouldLight = value != 0;
                    }
                    else
                    {
                        shouldLight = lightValueMap.get(ent.getClass()) != 0;
                    }
                    
                    if (!shouldLight)
                    {
                        continue;
                    }
                    
                    // now find them in the already tracked adapters
                    boolean found = false;
                    Iterator<EntityLightAdapter> iter = trackedEntities.iterator();
                    EntityLightAdapter adapter = null;
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
                        adapter = new EntityLightAdapter(ent);
                        adapter.onTick();
                        newList.add(adapter);
                    }
                }
            }
            // any remaining adapters were not targeted again, which probably means they dont burn anymore. The tick will finish them off.
            for (EntityLightAdapter adapter : trackedEntities)
            {
                adapter.onTick();
            }
            
            trackedEntities = newList;
            threadRunning = false;
        }
        
    }
    
    private class EntityLightAdapter implements IDynamicLightSource
    {
        
        private Entity entity;
        private int lightLevel;
        private boolean enabled;
        
        public EntityLightAdapter(Entity e)
        {
            lightLevel = 0;
            enabled = false;
            entity = e;
        }
        
        /**
         * Since they are IDynamicLightSource instances, they will already receive updates! Why do we need
         * to do this? Because seperate Thread!
         */
        public void onTick()
        {
            if (entity.isBurning())
            {
                lightLevel = 15;
            }
            else
            {
                lightLevel = 0;
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
            return entity;
        }

        @Override
        public int getLightLevel()
        {
            return lightLevel;
        }
    }

}
