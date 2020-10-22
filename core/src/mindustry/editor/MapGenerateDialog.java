package mindustry.editor;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.ui.*;
import arc.scene.ui.ImageButton.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import arc.util.async.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.io.*;
import mindustry.maps.filters.*;
import mindustry.maps.filters.GenerateFilter.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import mindustry.world.*;
import mindustry.world.blocks.environment.*;

import static mindustry.Vars.*;

@SuppressWarnings("unchecked")
public class MapGenerateDialog extends BaseDialog{
    private final Prov<GenerateFilter>[] filterTypes = new Prov[]{
        NoiseFilter::new, ScatterFilter::new, TerrainFilter::new, DistortFilter::new,
        RiverNoiseFilter::new, OreFilter::new, OreMedianFilter::new, MedianFilter::new,
        BlendFilter::new, MirrorFilter::new, ClearFilter::new, CoreSpawnFilter::new,
        EnemySpawnFilter::new, SpawnPathFilter::new
    };
    private final MapEditor editor;
    private final boolean applied;

    private Pixmap pixmap;
    private Texture texture;
    private GenerateInput input = new GenerateInput();
    Seq<GenerateFilter> filters = new Seq<>();
    private int scaling = mobile ? 3 : 1;
    private Table filterTable;

    private AsyncExecutor executor = new AsyncExecutor(1);
    private AsyncResult<Void> result;
    boolean generating;
    private GenTile returnTile = new GenTile();

    private GenTile[][] buffer1, buffer2;
    private Cons<Seq<GenerateFilter>> applier;
    CachedTile ctile = new CachedTile(){
        //nothing.
        @Override
        protected void changeBuild(Team team, Prov<Building> entityprov, int rotation){

        }
    };

    /** @param applied whether or not to use the applied in-game mode. */
    public MapGenerateDialog(MapEditor editor, boolean applied){
        super("@editor.generate");
        this.editor = editor;
        this.applied = applied;

        shown(this::setup);
        addCloseButton();
        if(applied){
            buttons.button("@editor.apply", Icon.ok, () -> {
                ui.loadAnd(() -> {
                    apply();
                    hide();
                });
            }).size(160f, 64f);
        }else{
            buttons.button("@settings.reset", () -> {
                filters.set(maps.readFilters(""));
                rebuildFilters();
                update();
            }).size(160f, 64f);
        }
        buttons.button("@editor.randomize", Icon.refresh, () -> {
            for(GenerateFilter filter : filters){
                filter.randomize();
            }
            update();
        }).size(160f, 64f);

        buttons.button("@add", Icon.add, this::showAdd).height(64f).width(150f);

        if(!applied){
            hidden(this::apply);
        }

        onResize(this::rebuildFilters);
    }

    public void show(Seq<GenerateFilter> filters, Cons<Seq<GenerateFilter>> applier){
        this.filters = filters;
        this.applier = applier;
        show();
    }

    public void show(Cons<Seq<GenerateFilter>> applier){
        show(this.filters, applier);
    }

    /** Applies the specified filters to the editor. */
    public void applyToEditor(Seq<GenerateFilter> filters){
        //writeback buffer
        GenTile[][] writeTiles = new GenTile[editor.width()][editor.height()];

        for(int x = 0; x < editor.width(); x++){
            for(int y = 0; y < editor.height(); y++){
                writeTiles[x][y] = new GenTile();
            }
        }

        for(GenerateFilter filter : filters){
            input.begin(filter, editor.width(), editor.height(), editor::tile);
            //write to buffer
            for(int x = 0; x < editor.width(); x++){
                for(int y = 0; y < editor.height(); y++){
                    Tile tile = editor.tile(x, y);
                    input.apply(x, y, tile.block(), tile.floor(), tile.overlay());
                    filter.apply(input);
                    writeTiles[x][y].set(input.floor, input.block, input.overlay, tile.team());
                }
            }

            editor.load(() -> {
                //read from buffer back into tiles
                for(int x = 0; x < editor.width(); x++){
                    for(int y = 0; y < editor.height(); y++){
                        Tile tile = editor.tile(x, y);
                        GenTile write = writeTiles[x][y];

                        //don't mess up synthetic stuff.
                        if(!tile.synthetic() && !content.block(write.block).synthetic()){
                            tile.setBlock(content.block(write.block));
                        }

                        tile.setFloor((Floor)content.block(write.floor));
                        tile.setTeam(Team.get(write.team));
                        tile.setOverlay(content.block(write.ore));
                    }
                }
            });
        }

        //reset undo stack as generation... messes things up
        editor.renderer.updateAll();
        editor.clearOp();
    }

    void setup(){
        if(pixmap != null){
            pixmap.dispose();
            texture.dispose();
            pixmap = null;
            texture = null;
        }

        pixmap = new Pixmap(editor.width() / scaling, editor.height() / scaling);
        texture = new Texture(pixmap);

        cont.clear();
        cont.table(t -> {
            t.margin(8f);
            t.stack(new BorderImage(texture){
                {
                    setScaling(Scaling.fit);
                }

                @Override
                public void draw(){
                    super.draw();
                    for(GenerateFilter filter : filters){
                        filter.draw(this);
                    }
                }
            }, new Stack(){{
                add(new Image(Styles.black8));
                add(new Image(Icon.refresh, Scaling.none));
                visible(() -> generating && !updateEditorOnChange);
            }}).uniformX().grow().padRight(10);
            t.pane(p -> filterTable = p.marginRight(6)).update(pane -> {
                if(Core.scene.getKeyboardFocus() instanceof Dialog && Core.scene.getKeyboardFocus() != this){
                    return;
                }

                Vec2 v = pane.stageToLocalCoordinates(Core.input.mouse());

                if(v.x >= 0 && v.y >= 0 && v.x <= pane.getWidth() && v.y <= pane.getHeight()){
                    Core.scene.setScrollFocus(pane);
                }else{
                    Core.scene.setScrollFocus(null);
                }
            }).grow().uniformX().get().setScrollingDisabled(true, false);
        }).grow();

        buffer1 = create();
        buffer2 = create();

        update();
        rebuildFilters();
    }

    GenTile[][] create(){
        GenTile[][] out = new GenTile[editor.width() / scaling][editor.height() / scaling];

        for(int x = 0; x < out.length; x++){
            for(int y = 0; y < out[0].length; y++){
                out[x][y] = new GenTile();
            }
        }
        return out;
    }

    void rebuildFilters(){
        int cols = Math.max((int)(Core.graphics.getWidth()/2f / Scl.scl(290f)), 1);
        filterTable.clearChildren();
        filterTable.top().left();
        int i = 0;

        for(GenerateFilter filter : filters){

            //main container
            filterTable.table(Tex.pane, c -> {
                c.margin(0);

                //icons to perform actions
                c.table(Tex.whiteui, t -> {
                    t.setColor(Pal.gray);

                    t.top().left();
                    t.add(filter.name()).left().padLeft(6).width(100f).wrap();

                    t.add().growX();

                    ImageButtonStyle style = Styles.geni;
                    t.defaults().size(42f);

                    t.button(Icon.refresh, style, () -> {
                        filter.randomize();
                        update();
                    });

                    t.button(Icon.upOpen, style, () -> {
                        int idx = filters.indexOf(filter);
                        filters.swap(idx, Math.max(0, idx - 1));
                        rebuildFilters();
                        update();
                    });
                    t.button(Icon.downOpen, style, () -> {
                        int idx = filters.indexOf(filter);
                        filters.swap(idx, Math.min(filters.size - 1, idx + 1));
                        rebuildFilters();
                        update();
                    });
                    t.button(Icon.cancel, style, () -> {
                        filters.remove(filter);
                        rebuildFilters();
                        update();
                    });
                }).growX();

                c.row();
                //all the options
                c.table(f -> {
                    f.left().top();
                    for(FilterOption option : filter.options()){
                        option.changed = this::update;

                        f.table(t -> {
                            t.left();
                            option.build(t);
                        }).growX().left();
                        f.row();
                    }
                }).grow().left().pad(6).top();
            }).width(280f).pad(3).top().left().fillY();


            if(++i % cols == 0){
                filterTable.row();
            }
        }

        if(filters.isEmpty()){
            filterTable.add("@filters.empty").wrap().width(200f);
        }
    }

    void showAdd(){
        BaseDialog selection = new BaseDialog("@add");
        selection.setFillParent(false);
        selection.cont.defaults().size(210f, 60f);
        int i = 0;
        for(Prov<GenerateFilter> gen : filterTypes){
            GenerateFilter filter = gen.get();

            if((filter.isPost() && applied)) continue;

            selection.cont.button(filter.name(), () -> {
                filters.add(filter);
                rebuildFilters();
                update();
                selection.hide();
            });
            if(++i % 2 == 0) selection.cont.row();
        }

        selection.cont.button("@filter.defaultores", () -> {
            maps.addDefaultOres(filters);
            rebuildFilters();
            update();
            selection.hide();
        });

        selection.addCloseButton();
        selection.show();
    }

    GenTile dset(Tile tile){
        returnTile.set(tile);
        return returnTile;
    }

    void apply(){
        if(result != null){
            result.get();
        }

        buffer1 = null;
        buffer2 = null;
        generating = false;
        if(pixmap != null){
            pixmap.dispose();
            texture.dispose();
            pixmap = null;
            texture = null;
        }

        applier.get(filters);
    }

    void update(){

        if(generating){
            return;
        }

        Seq<GenerateFilter> copy = new Seq<>(filters);

        result = executor.submit(() -> {
            try{
                world.setGenerating(true);
                generating = true;

                if(!filters.isEmpty()){
                    //write to buffer1 for reading
                    for(int px = 0; px < pixmap.getWidth(); px++){
                        for(int py = 0; py < pixmap.getHeight(); py++){
                            buffer1[px][py].set(editor.tile(px * scaling, py * scaling));
                        }
                    }
                }

                for(GenerateFilter filter : copy){
                    input.begin(filter, editor.width(), editor.height(), (x, y) -> buffer1[Mathf.clamp(x / scaling, 0, pixmap.getWidth()-1)][Mathf.clamp(y / scaling, 0, pixmap.getHeight()-1)].tile());

                    //read from buffer1 and write to buffer2
                    pixmap.each((px, py) -> {
                        int x = px * scaling, y = py * scaling;
                        GenTile tile = buffer1[px][py];
                        input.apply(x, y, content.block(tile.block), content.block(tile.floor), content.block(tile.ore));
                        filter.apply(input);
                        buffer2[px][py].set(input.floor, input.block, input.overlay, Team.get(tile.team));
                    });

                    pixmap.each((px, py) -> buffer1[px][py].set(buffer2[px][py]));
                }

                for(int px = 0; px < pixmap.getWidth(); px++){
                    for(int py = 0; py < pixmap.getHeight(); py++){
                        int color;
                        //get result from buffer1 if there's filters left, otherwise get from editor directly
                        if(filters.isEmpty()){
                            Tile tile = editor.tile(px * scaling, py * scaling);
                            color = MapIO.colorFor(tile.floor(), tile.block(), tile.overlay(), Team.derelict);
                        }else{
                            GenTile tile = buffer1[px][py];
                            color = MapIO.colorFor(content.block(tile.floor), content.block(tile.block), content.block(tile.ore), Team.derelict);
                        }
                        pixmap.draw(px, pixmap.getHeight() - 1 - py, color);
                    }
                }

                Core.app.post(() -> {
                    if(pixmap == null || texture == null){
                        return;
                    }
                    texture.draw(pixmap);
                    generating = false;
                });
            }catch(Exception e){
                generating = false;
                e.printStackTrace();
            }
            world.setGenerating(false);
        });
    }

    private class GenTile{
        public byte team;
        public short block, floor, ore;

        GenTile(){
        }

        public void set(Block floor, Block wall, Block ore, Team team){
            this.floor = floor.id;
            this.block = wall.id;
            this.ore = (!floor.asFloor().hasSurface() && ore.asFloor().needsSurface) ? 0 : ore.id;
            this.team = (byte)team.id;
        }

        public void set(GenTile other){
            this.floor = other.floor;
            this.block = other.block;
            this.ore = other.ore;
            this.team = other.team;
        }

        public GenTile set(Tile other){
            set(other.floor(), other.block(), other.overlay(), other.team());
            return this;
        }

        Tile tile(){
            ctile.setFloor((Floor)content.block(floor));
            ctile.setBlock(content.block(block));
            ctile.setOverlay(content.block(ore));
            ctile.setTeam(Team.get(team));
            return ctile;
        }
    }
}
