package io.anuke.mindustry.editor;

import io.anuke.arc.Core;
import io.anuke.arc.collection.Array;
import io.anuke.arc.collection.ObjectMap;
import io.anuke.arc.files.FileHandle;
import io.anuke.arc.function.Consumer;
import io.anuke.arc.graphics.Color;
import io.anuke.arc.graphics.Pixmap;
import io.anuke.arc.graphics.g2d.TextureRegion;
import io.anuke.arc.input.KeyCode;
import io.anuke.arc.math.Mathf;
import io.anuke.arc.math.geom.Vector2;
import io.anuke.arc.scene.actions.Actions;
import io.anuke.arc.scene.style.TextureRegionDrawable;
import io.anuke.arc.scene.ui.*;
import io.anuke.arc.scene.ui.layout.Table;
import io.anuke.arc.scene.ui.layout.Unit;
import io.anuke.arc.scene.utils.UIUtils;
import io.anuke.arc.util.*;
import io.anuke.mindustry.Vars;
import io.anuke.mindustry.core.Platform;
import io.anuke.mindustry.game.Team;
import io.anuke.mindustry.io.MapIO;
import io.anuke.mindustry.maps.Map;
import io.anuke.mindustry.maps.MapMeta;
import io.anuke.mindustry.maps.MapTileData;
import io.anuke.mindustry.ui.dialogs.FloatingDialog;
import io.anuke.mindustry.world.Block;
import io.anuke.mindustry.world.Block.Icon;
import io.anuke.mindustry.world.blocks.OreBlock;

import java.io.DataInputStream;
import java.io.InputStream;

import static io.anuke.mindustry.Vars.*;

public class MapEditorDialog extends Dialog implements Disposable{
    private MapEditor editor;
    private MapView view;
    private MapInfoDialog infoDialog;
    private MapLoadDialog loadDialog;
    private MapResizeDialog resizeDialog;
    private ScrollPane pane;
    private FloatingDialog menu;
    private boolean saved = false;
    private boolean shownWithMap = false;
    private Array<Block> blocksOut = new Array<>();

    private ButtonGroup<ImageButton> blockgroup;

    public MapEditorDialog(){
        super("", "dialog");

        background("dark");

        editor = new MapEditor();
        view = new MapView(editor);

        infoDialog = new MapInfoDialog(editor);

        menu = new FloatingDialog("$menu");
        menu.addCloseButton();

        float isize = 16 * 2f;
        float swidth = 180f;

        menu.cont.table(t -> {
            t.defaults().size(swidth, 60f).padBottom(5).padRight(5).padLeft(5);

            t.addImageTextButton("$editor.savemap", "icon-floppy-16", isize, this::save).size(swidth * 2f + 10, 60f).colspan(2);

            t.row();

            t.addImageTextButton("$editor.mapinfo", "icon-pencil", isize, () -> {
                infoDialog.show();
                menu.hide();
            });

            t.addImageTextButton("$editor.resize", "icon-resize", isize, () -> {
                resizeDialog.show();
                menu.hide();
            });

            t.row();

            t.addImageTextButton("$editor.import", "icon-load-map", isize, () ->
                    createDialog("$editor.import",
                            "$editor.importmap", "$editor.importmap.description", "icon-load-map", (Runnable) loadDialog::show,
                            "$editor.importfile", "$editor.importfile.description", "icon-file", (Runnable) () ->
                                Platform.instance.showFileChooser("$loadimage", "Map Files", file -> ui.loadAnd(() -> {
                                    try{
                                        DataInputStream stream = new DataInputStream(file.read());

                                        MapMeta meta = MapIO.readMapMeta(stream);
                                        MapTileData data = MapIO.readTileData(stream, meta, false);

                                        editor.beginEdit(data, meta.tags, false);
                                        view.clearStack();
                                    }catch(Exception e){
                                        ui.showError(Core.bundle.format("editor.errorimageload", Strings.parseException(e, false)));
                                        Log.err(e);
                                    }
                                }), true, mapExtension),

						"$editor.importimage", "$editor.importimage.description", "icon-file-image", (Runnable)() ->
                            Platform.instance.showFileChooser("$loadimage", "Image Files", file ->
                                ui.loadAnd(() -> {
                                    try{
                                        MapTileData data = MapIO.readLegacyPixmap(new Pixmap(file));

                                        editor.beginEdit(data, editor.getTags(), false);
                                        view.clearStack();
                                    }catch (Exception e){
                                        ui.showError(Core.bundle.format("editor.errorimageload", Strings.parseException(e, false)));
                                        Log.err(e);
                                    }
                                }), true, "png")
                    ));

            t.addImageTextButton("$editor.export", "icon-save-map", isize, () -> createDialog("$editor.export",
                    "$editor.exportfile", "$editor.exportfile.description", "icon-file", (Runnable) () ->
                        Platform.instance.showFileChooser("$saveimage", "Map Files", file -> {
                            file = file.parent().child(file.nameWithoutExtension() + "." + mapExtension);
                            FileHandle result = file;
                            ui.loadAnd(() -> {

                                try{
                                    if(!editor.getTags().containsKey("name")){
                                        editor.getTags().put("name", result.nameWithoutExtension());
                                    }
                                    MapIO.writeMap(result.write(false), editor.getTags(), editor.getMap());
                                }catch(Exception e){
                                    ui.showError(Core.bundle.format("editor.errorimagesave", Strings.parseException(e, false)));
                                    Log.err(e);
                                }
                            });
                        }, false, mapExtension)));

            t.row();

            t.row();
        });

        menu.cont.row();

        menu.cont.addImageTextButton("$quit", "icon-back", isize, () -> {
            tryExit();
            menu.hide();
        }).padTop(-5).size(swidth * 2f + 10, 60f);

        resizeDialog = new MapResizeDialog(editor, (x, y) -> {
            if(!(editor.getMap().width() == x && editor.getMap().height() == y)){
                ui.loadAnd(() -> {
                    editor.resize(x, y);
                    view.clearStack();
                });
            }
        });

        loadDialog = new MapLoadDialog(map ->
            ui.loadAnd(() -> {
                try(DataInputStream stream = new DataInputStream(map.stream.get())){
                    MapMeta meta = MapIO.readMapMeta(stream);
                    MapTileData data = MapIO.readTileData(stream, meta, false);

                    editor.beginEdit(data, meta.tags, false);
                    view.clearStack();
                }catch(Exception e){
                    ui.showError(Core.bundle.format("editor.errorimageload", Strings.parseException(e, false)));
                    Log.err(e);
                }
            }));

        setFillParent(true);

        clearChildren();
        margin(0);
        shown(this::build);

        update(() -> {
            if(Core.scene.getKeyboardFocus() instanceof Dialog && Core.scene.getKeyboardFocus() != this){
                return;
            }

            Vector2 v = pane.stageToLocalCoordinates(Core.input.mouse());

            if(v.x >= 0 && v.y >= 0 && v.x <= pane.getWidth() && v.y <= pane.getHeight()){
                Core.scene.setScrollFocus(pane);
            }else{
                Core.scene.setScrollFocus(null);
            }

            if(Core.scene != null && Core.scene.getKeyboardFocus() == this){
                doInput();
            }
        });

        shown(() -> {
            saved = true;
            Platform.instance.beginForceLandscape();
            view.clearStack();
            Core.scene.setScrollFocus(view);
            if(!shownWithMap){
                editor.beginEdit(new MapTileData(200, 200), new ObjectMap<>(), true);
            }
            shownWithMap = false;

            Time.runTask(10f, Platform.instance::updateRPC);
        });

        hidden(() -> {
            Platform.instance.updateRPC();
            Platform.instance.endForceLandscape();
        });
    }

    @Override
    protected void drawBackground(float x, float y){
        drawDefaultBackground(x, y);
    }

    private void save(){
        String name = editor.getTags().get("name", "");

        if(name.isEmpty()){
            ui.showError("$editor.save.noname");
        }else{
            Map map = world.maps.getByName(name);
            if(map != null && !map.custom){
                ui.showError("$editor.save.overwrite");
            }else{
                world.maps.saveMap(name, editor.getMap(), editor.getTags());
                ui.showInfoFade("$editor.saved");
            }
        }

        menu.hide();
        saved = true;
    }

    /**
     * Argument format:
     * 0) button name
     * 1) description
     * 2) icon name
     * 3) listener
     */
    private void createDialog(String title, Object... arguments){
        FloatingDialog dialog = new FloatingDialog(title);

        float h = 90f;

        dialog.cont.defaults().size(360f, h).padBottom(5).padRight(5).padLeft(5);

        for(int i = 0; i < arguments.length; i += 4){
            String name = (String) arguments[i];
            String description = (String) arguments[i + 1];
            String iconname = (String) arguments[i + 2];
            Runnable listenable = (Runnable) arguments[i + 3];

            TextButton button = dialog.cont.addButton(name, () -> {
                listenable.run();
                dialog.hide();
                menu.hide();
            }).left().margin(0).get();

            button.clearChildren();
            button.addImage(iconname).size(16 * 3).padLeft(10);
            button.table(t -> {
                t.add(name).growX().wrap();
                t.row();
                t.add(description).color(Color.GRAY).growX().wrap();
            }).growX().pad(10f).padLeft(5);

            button.row();

            dialog.cont.row();
        }

        dialog.addCloseButton();
        dialog.show();
    }

    @Override
    public Dialog show(){
        return super.show(Core.scene, Actions.sequence(Actions.alpha(0f), Actions.scaleTo(1f, 1f), Actions.fadeIn(0.3f)));
    }

    @Override
    public void dispose(){
        editor.renderer().dispose();
    }

    public void beginEditMap(InputStream is){
        ui.loadAnd(() -> {
            try{
                shownWithMap = true;
                DataInputStream stream = new DataInputStream(is);
                MapMeta meta = MapIO.readMapMeta(stream);
                editor.beginEdit(MapIO.readTileData(stream, meta, false), meta.tags, false);
                is.close();
                show();
            }catch(Exception e){
                Log.err(e);
                ui.showError(Core.bundle.format("editor.errorimageload", Strings.parseException(e, false)));
            }
        });
    }

    public MapView getView(){
        return view;
    }

    public void resetSaved(){
        saved = false;
    }

    public void updateSelectedBlock(){
        Block block = editor.getDrawBlock();
        for(int j = 0; j < Vars.content.blocks().size; j++){
            if(block.id == j && j < blockgroup.getButtons().size){
                blockgroup.getButtons().get(j).setChecked(true);
                break;
            }
        }
    }

    public boolean hasPane(){
        return Core.scene.getScrollFocus() == pane || Core.scene.getKeyboardFocus() != this;
    }

    public void build(){
        float amount = 10f, baseSize = 60f;

        float size = mobile ? (int) (Math.min(Core.graphics.getHeight(), Core.graphics.getWidth()) / amount / Unit.dp.scl(1f)) :
                Math.min(Core.graphics.getDisplayMode().height / amount, baseSize);

        clearChildren();
        table(cont -> {
            cont.left();

            cont.table(mid -> {
                mid.top();

                Table tools = new Table().top();

                ButtonGroup<ImageButton> group = new ButtonGroup<>();

                Consumer<EditorTool> addTool = tool -> {
                    ImageButton button = new ImageButton("icon-" + tool.name(), "clear-toggle");
                    button.clicked(() -> view.setTool(tool));
                    button.resizeImage(16 * 2f);
                    button.update(() -> button.setChecked(view.getTool() == tool));
                    group.add(button);
                    if(tool == EditorTool.pencil)
                        button.setChecked(true);

                    tools.add(button);
                };

                tools.defaults().size(size, size);

                tools.addImageButton("icon-menu-large", "clear", 16 * 2f, menu::show);

                ImageButton grid = tools.addImageButton("icon-grid", "clear-toggle", 16 * 2f, () -> view.setGrid(!view.isGrid())).get();

                addTool.accept(EditorTool.zoom);

                tools.row();

                ImageButton undo = tools.addImageButton("icon-undo", "clear", 16 * 2f, () -> view.undo()).get();
                ImageButton redo = tools.addImageButton("icon-redo", "clear", 16 * 2f, () -> view.redo()).get();

                addTool.accept(EditorTool.pick);

                tools.row();

                undo.setDisabled(() -> !view.getStack().canUndo());
                redo.setDisabled(() -> !view.getStack().canRedo());

                undo.update(() -> undo.getImage().setColor(undo.isDisabled() ? Color.GRAY : Color.WHITE));
                redo.update(() -> redo.getImage().setColor(redo.isDisabled() ? Color.GRAY : Color.WHITE));
                grid.update(() -> grid.setChecked(view.isGrid()));

                addTool.accept(EditorTool.line);
                addTool.accept(EditorTool.pencil);
                addTool.accept(EditorTool.eraser);

                tools.row();

                addTool.accept(EditorTool.fill);
                addTool.accept(EditorTool.spray);

                ImageButton rotate = tools.addImageButton("icon-arrow-16", "clear", 16 * 2f, () -> editor.setDrawRotation((editor.getDrawRotation() + 1) % 4)).get();
                rotate.getImage().update(() -> {
                    rotate.getImage().setRotation(editor.getDrawRotation() * 90);
                    rotate.getImage().setOrigin(Align.center);
                });

                tools.row();

                tools.table("underline", t -> t.add("$editor.teams"))
                        .colspan(3).height(40).width(size * 3f + 3f).padBottom(3);

                tools.row();

                ButtonGroup<ImageButton> teamgroup = new ButtonGroup<>();

                int i = 0;

                for(Team team : Team.all){
                    ImageButton button = new ImageButton("white", "clear-toggle-partial");
                    button.margin(4f);
                    button.getImageCell().grow();
                    button.getStyle().imageUpColor = team.color;
                    button.clicked(() -> editor.setDrawTeam(team));
                    button.update(() -> button.setChecked(editor.getDrawTeam() == team));
                    teamgroup.add(button);
                    tools.add(button);

                    if(i++ % 3 == 2) tools.row();
                }

                mid.add(tools).top().padBottom(-6);

                mid.row();

                mid.table("underline", t -> {
                    Slider slider = new Slider(0, MapEditor.brushSizes.length - 1, 1, false);
                    slider.moved(f -> editor.setBrushSize(MapEditor.brushSizes[(int) (float) f]));

                    t.top();
                    t.add("$editor.brush");
                    t.row();
                    t.add(slider).width(size * 3f - 20).padTop(4f);
                }).padTop(5).growX().top();

            }).margin(0).left().growY();


            cont.table(t -> t.add(view).grow()).grow();

            cont.table(this::addBlockSelection).right().growY();

        }).grow();
    }

    private void doInput(){
        //tool select
        for(int i = 0; i < EditorTool.values().length; i++){
            if(Core.input.keyTap(KeyCode.valueOf("NUM_" + (i + 1)))){
                view.setTool(EditorTool.values()[i]);
                break;
            }
        }

        if(Core.input.keyTap(KeyCode.R)){
            editor.setDrawRotation((editor.getDrawRotation() + 1) % 4);
        }

        if(Core.input.keyTap(KeyCode.E)){
            editor.setDrawRotation(Mathf.mod((editor.getDrawRotation() + 1), 4));
        }

        //ctrl keys (undo, redo, save)
        if(UIUtils.ctrl()){
            if(Core.input.keyTap(KeyCode.Z)){
                view.undo();
            }

            if(Core.input.keyTap(KeyCode.Y)){
                view.redo();
            }

            if(Core.input.keyTap(KeyCode.S)){
                save();
            }

            if(Core.input.keyTap(KeyCode.G)){
                view.setGrid(!view.isGrid());
            }
        }
    }

    private void tryExit(){
        if(!saved){
            ui.showConfirm("$confirm", "$editor.unsaved", this::hide);
        }else{
            hide();
        }
    }

    private void addBlockSelection(Table table){
        Table content = new Table();
        pane = new ScrollPane(content);
        pane.setFadeScrollBars(false);
        pane.setOverscroll(true, false);
        ButtonGroup<ImageButton> group = new ButtonGroup<>();
        blockgroup = group;

        int i = 0;

        blocksOut.clear();
        blocksOut.addAll(Vars.content.blocks());
        blocksOut.sort((b1, b2) -> b1.synthetic() && !b2.synthetic() ? 1 : b2.synthetic() && !b1.synthetic() ? -1 :
            b1 instanceof OreBlock && !(b2 instanceof OreBlock) ? 1 : !(b1 instanceof OreBlock) && b2 instanceof OreBlock ? -1 :
            Integer.compare(b1.id, b2.id));

        for(Block block : blocksOut){
            TextureRegion region = block.icon(Icon.medium);

            if(!Core.atlas.isFound(region)) continue;

            ImageButton button = new ImageButton("white", "clear-toggle");
            button.getStyle().imageUp = new TextureRegionDrawable(region);
            button.clicked(() -> editor.setDrawBlock(block));
            button.resizeImage(8 * 4f);
            button.update(() -> button.setChecked(editor.getDrawBlock() == block));
            group.add(button);
            content.add(button).size(50f);

            if(++i % 4 == 0){
                content.row();
            }
        }

        group.getButtons().get(2).setChecked(true);

        table.table("underline", extra -> extra.labelWrap(() -> editor.getDrawBlock().localizedName).width(200f).center()).growX();
        table.row();
        table.add(pane).growY().fillX();
    }
}
