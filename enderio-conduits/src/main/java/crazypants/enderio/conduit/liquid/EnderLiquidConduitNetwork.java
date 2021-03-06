package crazypants.enderio.conduit.liquid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.enderio.core.common.fluid.IFluidWrapper;
import com.enderio.core.common.fluid.IFluidWrapper.ITankInfoWrapper;
import com.enderio.core.common.util.BlockCoord;
import com.enderio.core.common.util.RoundRobinIterator;

import crazypants.enderio.base.conduit.ConnectionMode;
import crazypants.enderio.base.config.Config;
import crazypants.enderio.conduit.AbstractConduitNetwork;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;

public class EnderLiquidConduitNetwork extends AbstractConduitNetwork<ILiquidConduit, EnderLiquidConduit> {

  public static final int MAX_EXTRACT_PER_TICK = Config.enderFluidConduitExtractRate;
  public static final int MAX_IO_PER_TICK = Config.enderFluidConduitMaxIoRate;

  List<NetworkTank> tanks = new ArrayList<NetworkTank>();
  Map<NetworkTankKey, NetworkTank> tankMap = new HashMap<NetworkTankKey, NetworkTank>();

  Map<NetworkTank, RoundRobinIterator<NetworkTank>> iterators;

  boolean filling;

  public EnderLiquidConduitNetwork() {
    super(EnderLiquidConduit.class, ILiquidConduit.class);
  }

  public void connectionChanged(EnderLiquidConduit con, EnumFacing conDir) {
    NetworkTankKey key = new NetworkTankKey(con, conDir);
    NetworkTank tank = new NetworkTank(con, conDir);
    tanks.remove(tank); // remove old tank, NB: =/hash is only calced on location and dir
    tankMap.remove(key);
    tanks.add(tank);
    tankMap.put(key, tank);
  }

  public boolean extractFrom(EnderLiquidConduit con, EnumFacing conDir) {
    NetworkTank tank = getTank(con, conDir);
    if(tank == null || !tank.isValid()) {
      return false;
    }
    FluidStack drained = tank.externalTank.getAvailableFluid();
    if(drained == null || drained.amount <= 0 || !matchedFilter(drained, con, conDir, true)) {
      return false;
    }
    drained = drained.copy();
    drained.amount = Math.min(drained.amount, MAX_EXTRACT_PER_TICK);
    int amountAccepted = fillFrom(tank, drained.copy(), true);
    if(amountAccepted <= 0) {
      return false;
    }
    drained.amount = amountAccepted;
    drained = tank.externalTank.drain(drained);
    if(drained == null || drained.amount <= 0) {
      return false;
    }
    //    if(drained.amount != amountAccepted) {
    //      Log.warn("EnderLiquidConduit.extractFrom: Extracted fluid volume is not equal to inserted volume. Drained=" + drained.amount + " filled="
    //          + amountAccepted + " Fluid: " + drained + " Accepted=" + amountAccepted);
    //    }
    return true;
  }

  private NetworkTank getTank(EnderLiquidConduit con, EnumFacing conDir) {
    return tankMap.get(new NetworkTankKey(con, conDir));
  }

  public int fillFrom(EnderLiquidConduit con, EnumFacing conDir, FluidStack resource, boolean doFill) {
    return fillFrom(getTank(con, conDir), resource, doFill);
  }

  public int fillFrom(NetworkTank tank, FluidStack resource, boolean doFill) {

    if(filling) {
      return 0;
    }

    try {

      filling = true;

      if(resource == null || tank == null || !matchedFilter(resource, tank.con, tank.conDir, true)) {
        return 0;
      }
      resource = resource.copy();
      resource.amount = Math.min(resource.amount, MAX_IO_PER_TICK);
      int filled = 0;
      int remaining = resource.amount;
      //TODO: Only change starting pos of iterator is doFill is true so a false then true returns the same

      for (NetworkTank target : getIteratorForTank(tank)) {
        if(!target.equals(tank) && target.acceptsOuput && target.isValid() && matchedFilter(resource, target.con, target.conDir, false)) {
          int vol = doFill ? target.externalTank.fill(resource.copy()) : target.externalTank.offer(resource.copy());
          remaining -= vol;
          filled += vol;
          if(remaining <= 0) {
            return filled;
          }
          resource.amount = remaining;
        }
      }
      return filled;

    } finally {
      filling = false;
    }
  }

  private boolean matchedFilter(FluidStack drained, EnderLiquidConduit con, EnumFacing conDir, boolean isInput) {
    if(drained == null || con == null || conDir == null) {
      return false;
    }
    FluidFilter filter = con.getFilter(conDir, isInput);
    if(filter == null || filter.isEmpty()) {
      return true;
    }
    return filter.matchesFilter(drained);
  }

  private Iterable<NetworkTank> getIteratorForTank(NetworkTank tank) {
    if(iterators == null) {
      iterators = new HashMap<NetworkTank, RoundRobinIterator<NetworkTank>>();
    }
    RoundRobinIterator<NetworkTank> res = iterators.get(tank);
    if(res == null) {
      res = new RoundRobinIterator<NetworkTank>(tanks);
      iterators.put(tank, res);
    }
    return res;
  }

  public FluidTankInfo[] getTankInfo(EnderLiquidConduit con, EnumFacing conDir) {
    List<FluidTankInfo> res = new ArrayList<FluidTankInfo>(tanks.size());
    NetworkTank tank = getTank(con, conDir);
    for (NetworkTank target : tanks) {
      if(!target.equals(tank) && target.isValid()) {
        for (ITankInfoWrapper info : target.externalTank.getTankInfoWrappers()) {
          res.add(info.getFluidTankInfo());
        }
      }
    }
    return res.toArray(new FluidTankInfo[res.size()]);
  }

  static class NetworkTankKey {

    EnumFacing conDir;
    BlockCoord conduitLoc;

    public NetworkTankKey(EnderLiquidConduit con, EnumFacing conDir) {
      this(con.getLocation(), conDir);
    }

    public NetworkTankKey(BlockCoord conduitLoc, EnumFacing conDir) {
      this.conDir = conDir;
      this.conduitLoc = conduitLoc;
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + ((conDir == null) ? 0 : conDir.hashCode());
      result = prime * result + ((conduitLoc == null) ? 0 : conduitLoc.hashCode());
      return result;
    }

    @Override
    public boolean equals(Object obj) {
      if(this == obj) {
        return true;
      }
      if(obj == null) {
        return false;
      }
      if(getClass() != obj.getClass()) {
        return false;
      }
      NetworkTankKey other = (NetworkTankKey) obj;
      if(conDir != other.conDir) {
        return false;
      }
      if(conduitLoc == null) {
        if(other.conduitLoc != null) {
          return false;
        }
      } else if(!conduitLoc.equals(other.conduitLoc)) {
        return false;
      }
      return true;
    }

  }

  static class NetworkTank {

    EnderLiquidConduit con;
    EnumFacing conDir;
    IFluidWrapper externalTank;
    EnumFacing tankDir;
    BlockCoord conduitLoc;
    boolean acceptsOuput;

    public NetworkTank(EnderLiquidConduit con, EnumFacing conDir) {
      this.con = con;
      this.conDir = conDir;
      conduitLoc = con.getLocation();
      tankDir = conDir.getOpposite();
      externalTank = AbstractLiquidConduit.getExternalFluidHandler(con.getBundle().getBundleworld(), conduitLoc.getLocation(conDir).getBlockPos(), tankDir);
      acceptsOuput = con.getConnectionMode(conDir).acceptsOutput();
    }

    public boolean isValid() {
      return externalTank != null && con.getConnectionMode(conDir) != ConnectionMode.DISABLED;
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + ((conDir == null) ? 0 : conDir.hashCode());
      result = prime * result + ((conduitLoc == null) ? 0 : conduitLoc.hashCode());
      return result;
    }

    @Override
    public boolean equals(Object obj) {
      if(this == obj) {
        return true;
      }
      if(obj == null) {
        return false;
      }
      if(getClass() != obj.getClass()) {
        return false;
      }
      NetworkTank other = (NetworkTank) obj;
      if(conDir != other.conDir) {
        return false;
      }
      if(conduitLoc == null) {
        if(other.conduitLoc != null) {
          return false;
        }
      } else if(!conduitLoc.equals(other.conduitLoc)) {
        return false;
      }
      return true;
    }

  }

}
