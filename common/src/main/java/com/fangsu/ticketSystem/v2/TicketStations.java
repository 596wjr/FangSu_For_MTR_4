package com.fangsu.ticketSystem.v2;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;
import static com.fangsu.ticketSystem.v2.TicketDefinitions.*;
public final class TicketStations {
    public static Station at(Level world, BlockPos pos) {
        var station = com.fangsu.ticketSystem.MtrTicketSystem.getStation(world,pos);
        if (station == null) return null;
        Station result = new Station();
        result.id = station.getId(); result.name = station.getName();
        result.x = (station.getMinX()+station.getMaxX())/2.0; result.z = (station.getMinZ()+station.getMaxZ())/2.0;
        result.zones = new int[]{(int)station.getZone1(),(int)station.getZone2(),(int)station.getZone3()};

        result.dimension = world.dimension().location().toString(); result.key = result.dimension + "/" + result.id; return result;
    }
    /** 与对应原版MTR一致：实际站名及单/三分区，语言分隔符替换为空格。 */
    public static String formatName(Station station) {
        return String.format("%s (%s/%s/%s)",
                station.name.replace('|', ' '),
                station.zones[0], station.zones[1], station.zones[2]);
    }
    public static void payMtr(ServerPlayer player,int amount) {
        Level world=player.getCommandSenderWorld();
        com.fangsu.ticketSystem.MtrTicketSystem.addObjectivesIfMissing(world);
        var score=com.fangsu.ticketSystem.MtrTicketSystem.getScore(world,player,"mtr_balance");
        //#if MC_VERSION >= 12002
        //$$ require(score.get() >= amount,"msg.fangsu.ticketing.insufficient_mtr_balance");
        //#else
        require(score.getScore() >= amount,"msg.fangsu.ticketing.insufficient_mtr_balance");
        //#endif
        score.add(-amount);
    }
    private TicketStations() { }
}
