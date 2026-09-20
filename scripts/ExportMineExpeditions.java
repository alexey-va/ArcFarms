import com.google.gson.GsonBuilder;
import ru.ruscrafting.farms.domain.mine.expedition.*;
import java.nio.file.*;
import java.util.*;

/** Export the actual compiled generator, including the first machine position, for Atelier inspection. */
class ExportMineExpeditions {
    static int[] point(ExpeditionPoint p) { return new int[]{p.getX(), p.getY(), p.getZ()}; }
    public static void main(String[] args) throws Exception {
        var output = Path.of(args[0]);
        Files.createDirectories(output);
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 20921L;
        var json = new GsonBuilder().disableHtmlEscaping().create();
        for (var kind : MineExpeditionKind.values()) {
            var plan = MineExpeditionGenerator.INSTANCE.plan(kind, seed);
            var blocks = new LinkedHashMap<>(plan.getBlocks());
            var center = plan.getStations().get(kind == MineExpeditionKind.LAST_DESCENT ? "lift_top" : "ark_start");
            if (center != null) MineExpeditionMachines.INSTANCE.blocks(kind).forEach((p, block) ->
                blocks.put(new ExpeditionPoint(p.getX() + center.getX(), p.getY() + center.getY(), p.getZ() + center.getZ()), block));
            var palette = new LinkedHashMap<String, Object>();
            palette.put("stone", Map.of("block", "minecraft:stone", "color", "#666970"));
            var materials = new LinkedHashMap<String, String>();
            materials.put("minecraft:air", "air");
            for (String block : blocks.values()) if (!materials.containsKey(block)) {
                String id = "m" + materials.size();
                materials.put(block, id);
                palette.put(id, Map.of("block", block, "color", "#888888"));
            }
            var min = plan.getBounds().getMin(); var max = plan.getBounds().getMax();
            int dx = 1 - min.getX(), dz = 1 - min.getZ();
            int[] size = {max.getX() - min.getX() + 3, max.getY() + 7, max.getZ() - min.getZ() + 3};
            var operations = new ArrayList<Object>();
            operations.add(Map.of("op", "box", "from", new int[]{0,0,0}, "to", new int[]{size[0]-1,size[1]-1,size[2]-1}, "material", "stone"));
            for (int x=min.getX(); x<=max.getX(); x++) for (int y=min.getY(); y<=max.getY(); y++) {
                int z=min.getZ();
                while (z<=max.getZ()) {
                    var block=blocks.get(new ExpeditionPoint(x,y,z));
                    if (block==null) { z++; continue; }
                    int end=z;
                    while (end<max.getZ() && block.equals(blocks.get(new ExpeditionPoint(x,y,end+1)))) end++;
                    operations.add(Map.of("op","box","from",new int[]{x+dx,y+1,z+dz},"to",new int[]{x+dx,y+1,end+dz},"material",materials.get(block)));
                    z=end+1;
                }
            }
            var stations = new LinkedHashMap<String, int[]>();
            plan.getStations().forEach((id,p)->stations.put(id,new int[]{p.getX()+dx,p.getY()+1,p.getZ()+dz}));
            var samples = plan.getWalkingRoutes().stream().flatMap(Collection::stream).distinct().map(p -> new int[]{p.getX()+dx,p.getY()+1,p.getZ()+dz}).toList();
            var views = new ArrayList<Object>();
            var entry=plan.getSpawn();
            views.add(Map.of("id","entry","label","Вход","camera",new double[]{entry.getX()+dx+.5,entry.getY()+2.62,entry.getZ()+dz+.5},"target",new double[]{dx+.5,entry.getY()+1.5,dz+.5}));
            if (kind==MineExpeditionKind.LAST_DESCENT) {
                views.add(Map.of("id","lift","label","С платформы","camera",new double[]{dx+.5,55.62,dz+.5},"target",new double[]{dx+.5,35,dz+14.5}));
                views.add(Map.of("id","engine","label","Глубинная машина","camera",new double[]{dx+5.5,11.62,dz-3.5},"target",new double[]{dx+.5,19,dz-20.0}));
            }
            if (kind==MineExpeditionKind.DRILLING_ARK) views.add(Map.of("id","crawler","label","Ковчег","camera",new double[]{dx+8.5,16.62,dz-13.5},"target",new double[]{dx+.5,15,dz-21.5}));
            if (kind==MineExpeditionKind.DEAD_FACTORY) views.add(Map.of("id","hall","label","Цех","camera",new double[]{dx+.5,13.62,dz+16.5},"target",new double[]{dx+.5,18,dz-4.5}));
            var scene = new LinkedHashMap<String,Object>();
            scene.put("version",1); scene.put("seed",seed); scene.put("title",kind.name());
            scene.put("subtitle","ArcFarms: compiled procedural geometry; static preview");
            scene.put("size",size); scene.put("palette",palette); scene.put("operations",operations);
            scene.put("origin",new int[]{entry.getX()+dx,entry.getY(),entry.getZ()+dz});
            scene.put("preview",Map.of("defaultView","entry","views",views));
            scene.put("lighting",Map.of("targetLevel",6,"samples",samples));
            Files.writeString(output.resolve(kind.name().toLowerCase()+".atelier.json"),json.toJson(scene));
            Files.writeString(output.resolve(kind.name().toLowerCase()+".stations.json"),json.toJson(stations));
            System.out.println(kind+" cells="+blocks.size()+" operations="+operations.size()+" routeSamples="+samples.size());
        }
    }
}
