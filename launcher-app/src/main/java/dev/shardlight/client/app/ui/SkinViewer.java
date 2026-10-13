package dev.shardlight.client.app.ui;

import dev.shardlight.client.i18n.I18n;
import dev.shardlight.client.skin.SkinUv;
import dev.shardlight.client.skin.SkinVariant;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Pos;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.DrawMode;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;

import java.util.List;

/**
 * A player model built from a skin that turns with the mouse: drag to rotate, scroll to zoom, arrow keys and
 * + / - do the same from the keyboard, Home puts it back. It slowly turns by itself until touched (not with
 * Reduce motion). Where the computer cannot draw 3D, the front and the back of the skin are shown flat instead.
 */
public final class SkinViewer extends StackPane {
    private static final double MIN_DISTANCE = 46;
    private static final double MAX_DISTANCE = 140;
    private static final double START_DISTANCE = 78;
    private static final double START_YAW = 28;
    private static final double START_PITCH = 8;

    private final boolean three;
    private final StackPane flat = new StackPane();
    private final Group pivot = new Group();
    private final DoubleProperty yaw = new SimpleDoubleProperty(START_YAW);
    private final DoubleProperty pitch = new SimpleDoubleProperty(START_PITCH);
    private final PerspectiveCamera camera;
    private Motion.Spinner spinner;
    private double dragX;
    private double dragY;
    private Image skin;
    private Image cape;
    private SkinVariant variant = SkinVariant.CLASSIC;

    public SkinViewer(double width, double height) {
        getStyleClass().add("skin-viewer");
        setMinSize(width, height);
        setPrefSize(width, height);
        setMaxSize(width, height);
        setFocusTraversable(true);
        three = Platform.isSupported(ConditionalFeature.SCENE3D);
        camera = three ? new PerspectiveCamera(true) : null;
        getChildren().add(flat);
        flat.setAlignment(Pos.CENTER);
        if (three) {
            Group world = new Group(pivot);
            // pitch about the screen's horizontal axis after the turn about the player's own vertical axis
            Rotate pitchRotation = new Rotate(0, Rotate.X_AXIS);
            Rotate yawRotation = new Rotate(0, Rotate.Y_AXIS);
            pitchRotation.angleProperty().bind(pitch);
            yawRotation.angleProperty().bind(yaw);
            pivot.getTransforms().addAll(pitchRotation, yawRotation, new Translate(0, 16, 0));
            AmbientLight ambient = new AmbientLight(Color.gray(0.82));
            PointLight key = new PointLight(Color.gray(0.45));
            key.setTranslateX(-40);
            key.setTranslateY(-60);
            key.setTranslateZ(-90);
            world.getChildren().addAll(ambient, key);
            SubScene sub = new SubScene(world, width, height, true, SceneAntialiasing.BALANCED);
            sub.setFill(Color.TRANSPARENT);
            camera.setNearClip(1);
            camera.setFarClip(600);
            camera.setFieldOfView(30);
            camera.setTranslateZ(-START_DISTANCE);
            sub.setCamera(camera);
            sub.widthProperty().bind(widthProperty());
            sub.heightProperty().bind(heightProperty());
            getChildren().add(0, sub);
            flat.setVisible(false);
            flat.setManaged(false);
            install();
            spinner = Motion.spin(this, yaw, 24);
        }
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        clip.setArcWidth(ThemeManager.em(2));
        clip.setArcHeight(ThemeManager.em(2));
        setClip(clip);
        setAccessibleText(I18n.tr("ui.skins.viewerLabel"));
        setAccessibleHelp(I18n.tr("ui.skins.viewerHelp"));
    }

    public boolean isThreeDimensional() {
        return three;
    }

    /** show {@code skin} (and {@code cape}, when there is one); either may be null */
    public void show(Image skin, SkinVariant variant, Image cape) {
        this.skin = skin;
        this.cape = cape;
        this.variant = variant;
        if (three) {
            rebuild();
        } else {
            rebuildFlat();
        }
    }

    /** hold the model still at these angles (the screenshots); the player can still take hold of it */
    public void freeze(double yawDegrees, double pitchDegrees) {
        yaw.set(yawDegrees);
        pitch.set(pitchDegrees);
        if (spinner != null) {
            spinner.pause(true);
        }
    }

    /** back to the opening view */
    public void resetView() {
        if (spinner != null) {
            spinner.pause(false);
        }
        yaw.set(START_YAW);
        pitch.set(START_PITCH);
        if (camera != null) {
            camera.setTranslateZ(-START_DISTANCE);
        }
    }

    // ---------------------------------------------------------------- interaction

    private void install() {
        setOnMousePressed(e -> {
            requestFocus();
            if (e.getButton() == MouseButton.PRIMARY) {
                dragX = e.getSceneX();
                dragY = e.getSceneY();
                touch();
            }
        });
        setOnMouseDragged(e -> {
            if (e.getButton() != MouseButton.PRIMARY) {
                return;
            }
            yaw.set(yaw.get() - (e.getSceneX() - dragX) * 0.6);
            pitch.set(clamp(pitch.get() + (e.getSceneY() - dragY) * 0.4, -40, 40));
            dragX = e.getSceneX();
            dragY = e.getSceneY();
        });
        setOnScroll(e -> {
            zoom(e.getDeltaY() > 0 ? -6 : 6);
            touch();
        });
        setOnKeyPressed(e -> {
            KeyCode k = e.getCode();
            if (k == KeyCode.LEFT) {
                yaw.set(yaw.get() + 15);
            } else if (k == KeyCode.RIGHT) {
                yaw.set(yaw.get() - 15);
            } else if (k == KeyCode.UP) {
                pitch.set(clamp(pitch.get() - 8, -40, 40));
            } else if (k == KeyCode.DOWN) {
                pitch.set(clamp(pitch.get() + 8, -40, 40));
            } else if (k == KeyCode.PLUS || k == KeyCode.EQUALS || k == KeyCode.ADD) {
                zoom(-6);
            } else if (k == KeyCode.MINUS || k == KeyCode.SUBTRACT) {
                zoom(6);
            } else if (k == KeyCode.HOME || k == KeyCode.DIGIT0) {
                resetView();
            } else {
                return;
            }
            touch();
            e.consume();
        });
    }

    /** once the player has taken hold of the model it stops turning by itself until the view is reset */
    private void touch() {
        spinner.pause(true);
    }

    private void zoom(double delta) {
        camera.setTranslateZ(-clamp(-camera.getTranslateZ() + delta, MIN_DISTANCE, MAX_DISTANCE));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---------------------------------------------------------------- 3D model

    private void rebuild() {
        pivot.getChildren().clear();
        if (skin == null) {
            return;
        }
        int unitsHigh = (int) Math.round(skin.getHeight() * 64.0 / skin.getWidth());
        PhongMaterial skinMaterial = material(skin);
        List<SkinUv.Box> boxes = SkinUv.boxes(unitsHigh, variant);
        // the inner layer first, then the outer layer, so the clear parts of the outer layer never hide anything
        for (boolean overlay : new boolean[] {false, true}) {
            for (SkinUv.Box b : boxes) {
                if (b.overlay() == overlay) {
                    pivot.getChildren().add(meshView(b, skinMaterial, unitsHigh, false));
                }
            }
        }
        if (cape != null) {
            int capeUnits = (int) Math.round(cape.getHeight() * 64.0 / cape.getWidth());
            MeshView cv = meshView(SkinUv.cape(), material(cape), capeUnits, true);
            // the cape hangs a little away from the back, tilted from the shoulders
            cv.getTransforms().addAll(new Translate(0, -24, 2.5), new Rotate(7, Rotate.X_AXIS), new Translate(0, 24, -2.5));
            pivot.getChildren().add(cv);
        }
    }

    private static PhongMaterial material(Image picture) {
        PhongMaterial m = new PhongMaterial(Color.WHITE);
        m.setDiffuseMap(SkinPixels.enlarge(picture, 8));
        m.setSpecularColor(Color.TRANSPARENT);
        return m;
    }

    private static MeshView meshView(SkinUv.Box b, PhongMaterial material, int textureUnitsHigh, boolean turned) {
        TriangleMesh mesh = new TriangleMesh();
        double g = b.grow();
        float x0 = (float) (b.x() - g);
        float x1 = (float) (b.x() + b.w() + g);
        float y0 = (float) (b.y() - g);
        float y1 = (float) (b.y() + b.h() + g);
        float z0 = (float) (b.z() - g);
        float z1 = (float) (b.z() + b.d() + g);
        SkinUv.Face[] faces = SkinUv.Face.values();
        int[] smoothing = new int[faces.length * 2];
        for (int fi = 0; fi < faces.length; fi++) {
            SkinUv.Face f = faces[fi];
            // the four corners: top-left, top-right, bottom-left, bottom-right as the face is seen from outside
            float[][] c = switch (f) {
                case FRONT -> new float[][] {{x0, y0, z0}, {x1, y0, z0}, {x0, y1, z0}, {x1, y1, z0}};
                case BACK -> new float[][] {{x1, y0, z1}, {x0, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
                case RIGHT -> new float[][] {{x0, y0, z1}, {x0, y0, z0}, {x0, y1, z1}, {x0, y1, z0}};
                case LEFT -> new float[][] {{x1, y0, z0}, {x1, y0, z1}, {x1, y1, z0}, {x1, y1, z1}};
                case TOP -> new float[][] {{x0, y0, z1}, {x1, y0, z1}, {x0, y0, z0}, {x1, y0, z0}};
                case BOTTOM -> new float[][] {{x0, y1, z0}, {x1, y1, z0}, {x0, y1, z1}, {x1, y1, z1}};
            };
            for (float[] p : c) {
                mesh.getPoints().addAll(p[0], p[1], p[2]);
            }
            SkinUv.Face source = turned ? SkinUv.turned(f) : f;
            if (b.mirror()) {
                // an old skin's left limbs are the right ones mirrored: the sides swap and every face reads backwards
                source = switch (source) {
                    case LEFT -> SkinUv.Face.RIGHT;
                    case RIGHT -> SkinUv.Face.LEFT;
                    default -> source;
                };
            }
            SkinUv.Rect r = b.face(source);
            float u0 = r.x() / 64f;
            float u1 = (r.x() + r.w()) / 64f;
            float v0 = r.y() / (float) textureUnitsHigh;
            float v1 = (r.y() + r.h()) / (float) textureUnitsHigh;
            if (b.mirror()) {
                float t = u0;
                u0 = u1;
                u1 = t;
            }
            mesh.getTexCoords().addAll(u0, v0, u1, v0, u0, v1, u1, v1);
            int p = fi * 4;
            // two triangles per face; the texture index of a corner equals its point index
            mesh.getFaces().addAll(p, p, p + 2, p + 2, p + 1, p + 1);
            mesh.getFaces().addAll(p + 1, p + 1, p + 2, p + 2, p + 3, p + 3);
            smoothing[fi * 2] = 1 << fi;
            smoothing[fi * 2 + 1] = 1 << fi;
        }
        mesh.getFaceSmoothingGroups().addAll(smoothing);
        MeshView view = new MeshView(mesh);
        view.setMaterial(material);
        view.setCullFace(CullFace.NONE);
        view.setDrawMode(DrawMode.FILL);
        return view;
    }

    // ---------------------------------------------------------------- flat fallback

    private void rebuildFlat() {
        flat.getChildren().clear();
        if (skin == null) {
            return;
        }
        ImageView front = sharp(SkinPixels.flatPlayer(skin, variant, false, 6));
        ImageView back = sharp(SkinPixels.flatPlayer(skin, variant, true, 6));
        HBox both = new HBox(16, front, back);
        both.setAlignment(Pos.CENTER);
        flat.getChildren().add(both);
    }

    private static ImageView sharp(Image img) {
        ImageView v = new ImageView(img);
        v.setSmooth(false);
        return v;
    }

}
