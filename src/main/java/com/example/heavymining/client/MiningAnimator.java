package com.example.heavymining.client;

import com.example.heavymining.HeavyMining;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Heavy Mining v3 - physical first-person mining.
 *
 *  - Pickaxe AND your arm are drawn in the world, so the head really sinks into blocks.
 *  - Always drawn by this mod while you hold a pickaxe: smooth blend hand -> swing -> hand, no popping.
 *  - Hit-based breaking: cracks grow only on a hit, block breaks on the last hit.
 *  - Continuous chopping rhythm across blocks.
 *  - Inertia sway, walk bob, breathing, arm recoil on impact, gentle camera dip.
 *
 * Mining is never faster than vanilla, so it is safe on servers.
 */
@EventBusSubscriber(modid = HeavyMining.MOD_ID, value = Dist.CLIENT)
public final class MiningAnimator {

    // ================================================================
    // TUNING
    // ================================================================

    // --- Timing ---
    /** Ticks per swing (20 ticks = 1 s). 4 = 5 hits per second. */
    private static final int SWING_TICKS = 4;
    /** More = more hits per block. Diamond pickaxe on stone is about 6 hits. */
    private static final float HITS_SCALE = 2.4f;
    /** How long to blend from your hand into the swing, and back (ticks). */
    private static final float BLEND_IN_TICKS = 3f;
    private static final float BLEND_OUT_TICKS = 6f;

    // --- Feel ---
    /** Camera dip per hit in degrees. 0 = off. (Old version was 1.1 with roll.) */
    private static final float CAMERA_SHAKE = 0.25f;
    /** Arm kick-back on each hit (blocks) and head bounce (degrees). */
    private static final float RECOIL_KICK = 0.03f;
    private static final float RECOIL_PITCH = 5f;
    /** Inertia sway when turning. 0 = off. */
    private static final float SWAY_STRENGTH = 0.14f;
    private static final float SWAY_MAX_DEGREES = 25f;
    /** Walking bob and idle breathing. 0 = off, 1 = normal. */
    private static final float WALK_BOB = 1f;

    // --- Idle hold (matched to your reference clip) ---
    /** Hand position while just holding the pickaxe: out to the right, below eye level. */
    private static final Vector3f HOLD_GRIP = new Vector3f(0.39f, -0.22f, -0.50f);
    /** Handle direction: up, out to the right, and leaning away from you. */
    private static final Vector3f HOLD_HANDLE = new Vector3f(0.47f, 0.43f, -0.76f);
    /** Which way the pick's point faces: in toward the middle of the screen. */
    private static final Vector3f HOLD_TIP = new Vector3f(-0.70f, -0.10f, -0.70f);
    /** Slow breathing loop. Keeps going in the pause menu, like the clip. */
    private static final float BREATH_PERIOD_SECONDS = 7f;
    private static final float BREATH_MOVE = 0.008f;   // blocks
    private static final float BREATH_TILT = 0.7f;     // degrees

    // --- Body ---
    /** Show your torso, legs and feet when you look down. */
    private static final boolean SHOW_BODY = true;
    /** How far the body sits behind your eyes (blocks), so you look down past your chest at your feet. */
    private static final float BODY_BACK_OFFSET = 0.25f;

    // --- Arm ---
    private static final boolean SHOW_ARM = true;
    /** Where your shoulder is relative to your eyes (x right, y up, -z forward). */
    private static final Vector3f SHOULDER = new Vector3f(0.36f, -0.62f, 0.12f);
    /** How much your body leans in when reaching for a block (0..1). */
    private static final float BODY_LEAN = 0.7f;
    private static final float ARM_LEN = 9f / 16f;
    private static final float ARM_STRETCH_MAX = 1.35f;

    // --- Pickaxe ---
    private static final float ITEM_SCALE = 0.75f;
    private static final float MAX_LUNGE = 0.5f;
    private static final float PENETRATION = 0.06f;
    private static final float WRIST_ROLL = -12f;

    private static final float RAISED_PITCH = 100f;
    private static final float COCK_PITCH = 108f;
    private static final float FOLLOW_PITCH = -6f;
    /** Where the swing starts from and lunges out of (separate from the idle hold). */
    private static final Vector3f REST_GRIP = new Vector3f(0.30f, -0.36f, -0.40f);
    private static final Vector3f RAISED_GRIP = new Vector3f(0.40f, -0.14f, -0.22f);
    private static final Vector3f AIR_SWING_REACH = new Vector3f(0f, -0.02f, -0.18f);
    private static final Vector3f REST_AIM = new Vector3f(0f, 0.35f, -1f).normalize();

    // Swing cycle (0..1); impact at 1.0 == 0.0
    private static final float HOLD_END = 0.10f;
    private static final float RECOIL_END = 0.34f;
    private static final float COCK_END = 0.46f;

    private static final int IMPACT_PARTICLES = 7;

    // Pickaxe sprite geometry (vanilla texture)
    private static final float GRIP_OFFSET = 0.33f;
    private static final Vector3f TIP_DIR = new Vector3f(0f, 0.47f, -0.48f).normalize();
    private static final float TIP_LEN = 0.67f * ITEM_SCALE;

    // ================================================================
    // STATE
    // ================================================================
    private static boolean wantsMining;      // attack held on a block with a pickaxe
    private static boolean active;           // vanilla is actually breaking our target
    private static boolean managed;
    private static boolean computing;
    private static BlockPos target;
    private static float vanillaPerTick;
    private static int hitsNeeded = 1;
    private static int hitsDone;
    private static boolean impactThisTick;

    private static Vec3 aimPoint;
    private static BlockState aimState;
    private static Direction aimFace = Direction.UP;

    private static int swingClock;
    private static int blendTicks;
    private static boolean blendingIn;
    private static float blendFrom;
    private static float lastW;

    private static float shake, prevShake;
    private static boolean playingOwnSound;

    private static ItemStack lastHandStack = ItemStack.EMPTY;
    private static float lastEquip;
    private static boolean rigDrawnThisFrame;
    private static boolean drawingFirstPersonBody;
    private static boolean bodyHideMainArm;

    private static final Quaternionf aimQ = new Quaternionf();
    private static boolean aimInit;
    private static long lastFrameNanos;

    private MiningAnimator() {}

    // ================================================================
    // TICK
    // ================================================================
    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        prevShake = shake;
        shake *= 0.55f;
        impactThisTick = false;

        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) {
            wantsMining = false;
            active = false;
            return;
        }

        BlockHitResult hit = (mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK) ? b : null;
        boolean pick = player.getMainHandItem().is(ItemTags.PICKAXES);
        boolean wants = pick && hit != null && mc.screen == null
                && mc.options.keyAttack.isDown() && !player.getAbilities().instabuild;

        // Blend weight bookkeeping
        if (wants != wantsMining) {
            blendFrom = lastW;
            blendTicks = 0;
            blendingIn = wants;
            if (wants && lastW < 0.3f) swingClock = 0; // fresh start: begin on the rise
        } else {
            blendTicks++;
        }
        wantsMining = wants;
        if (wantsMining || lastW > 0.001f) swingClock++;

        if (hit != null) {
            aimPoint = hit.getLocation();
            aimFace = hit.getDirection();
            aimState = mc.level.getBlockState(hit.getBlockPos());
        }

        // Track the block vanilla is breaking
        if (pick && hit != null && mc.gameMode.isDestroying()) {
            BlockPos pos = hit.getBlockPos();
            if (!active || !pos.equals(target)) startBlock(mc, player, pos);
        } else {
            active = false;
        }

        // Impacts happen on a steady clock, so the rhythm carries across blocks
        if (wantsMining && swingClock % SWING_TICKS == 0) {
            impactThisTick = true;
            if (active) hitsDone++;
            impactFx(mc);
        }
    }

    private static void startBlock(Minecraft mc, LocalPlayer player, BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        computing = true;
        try {
            vanillaPerTick = state.getDestroyProgress(player, mc.level, pos);
        } finally {
            computing = false;
        }
        managed = vanillaPerTick > 0f && vanillaPerTick < 1f;
        if (managed) {
            int vanillaTicks = (int) Math.ceil(1f / vanillaPerTick);
            int minHits = (int) Math.ceil(vanillaTicks / (float) SWING_TICKS);
            hitsNeeded = Math.max(1, Math.max(minHits, Math.round((float) Math.sqrt(vanillaTicks) * HITS_SCALE)));
        } else {
            hitsNeeded = 1;
        }
        target = pos.immutable();
        hitsDone = 0;
        active = true;
    }

    // ================================================================
    // BREAK SPEED: cracks grow only on a hit; block breaks on the last hit
    // ================================================================
    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        if (computing) return;
        Player p = event.getEntity();
        if (!p.level().isClientSide()) return;
        if (!p.getMainHandItem().is(ItemTags.PICKAXES)) return;
        BlockPos pos = event.getPosition().orElse(null);
        if (pos == null) return;

        if (active && pos.equals(target)) {
            if (!managed) return;
            if (!impactThisTick) {
                event.setNewSpeed(0f);
                return;
            }
            float delta = hitsDone >= hitsNeeded ? 1.05f : 1f / hitsNeeded + 1e-4f;
            event.setNewSpeed(event.getOriginalSpeed() * (delta / vanillaPerTick));
            return;
        }

        BlockState st = event.getState();
        float hardness = st.getDestroySpeed(p.level(), pos);
        if (hardness <= 0f) return;
        float perTick = event.getOriginalSpeed() / hardness / (p.hasCorrectToolForDrops(st) ? 30f : 100f);
        if (perTick < 1f) event.setNewSpeed(0f);
    }

    // ================================================================
    // IMPACT EFFECTS
    // ================================================================
    private static void impactFx(Minecraft mc) {
        shake = Math.max(shake, mc.options.screenEffectScale().get().floatValue());
        if (aimPoint == null || aimState == null || aimState.isAir()) return;

        RandomSource r = mc.level.random;
        BlockParticleOption chips = new BlockParticleOption(ParticleTypes.BLOCK, aimState);
        double nx = aimFace.getStepX(), ny = aimFace.getStepY(), nz = aimFace.getStepZ();
        for (int i = 0; i < IMPACT_PARTICLES; i++) {
            mc.level.addParticle(chips,
                    aimPoint.x + nx * 0.03, aimPoint.y + ny * 0.03, aimPoint.z + nz * 0.03,
                    nx * 0.10 + (r.nextDouble() - 0.5) * 0.15,
                    ny * 0.10 + r.nextDouble() * 0.10,
                    nz * 0.10 + (r.nextDouble() - 0.5) * 0.15);
        }

        SoundType sound = aimState.getSoundType();
        playingOwnSound = true;
        try {
            mc.level.playLocalSound(aimPoint.x, aimPoint.y, aimPoint.z, sound.getHitSound(),
                    SoundSource.BLOCKS, (sound.getVolume() + 1f) / 5f, sound.getPitch() * 0.6f, false);
        } finally {
            playingOwnSound = false;
        }
    }

    /** Mute vanilla's off-beat hit sound so the only hit sound lands on the impact. */
    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        if (playingOwnSound || !wantsMining || aimState == null || event.getSound() == null) return;
        if (event.getSound().getSource() != SoundSource.BLOCKS) return;
        if (event.getSound().getLocation().equals(aimState.getSoundType().getHitSound().getLocation())) {
            event.setSound(null);
        }
    }

    // ================================================================
    // RENDER
    // ================================================================
    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        lastHandStack = event.getItemStack();
        lastEquip = event.getEquipProgress();
        if (rigDrawnThisFrame) event.setCanceled(true);
        rigDrawnThisFrame = false;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        rigDrawnThisFrame = false;
        if (player == null || mc.level == null || player.isSpectator()
                || !mc.options.getCameraType().isFirstPerson() || mc.getCameraEntity() != player) {
            return;
        }
        float pt = mc.getTimer().getGameTimeDeltaPartialTick(false);
        boolean drawRig = !mc.options.hideGui && lastHandStack.is(ItemTags.PICKAXES);

        if (SHOW_BODY && !player.isInvisible()) {
            MultiBufferSource.BufferSource bodyBuffers = mc.renderBuffers().bufferSource();
            renderBody(mc, player, event.getCamera(), pt, bodyBuffers, drawRig && SHOW_ARM);
            bodyBuffers.endBatch();
        }
        if (!drawRig) return;

        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 0.016f : Math.min(0.1f, (now - lastFrameNanos) / 1e9f);
        lastFrameNanos = now;

        Camera cam = event.getCamera();
        Quaternionf camRot = new Quaternionf(cam.rotation());
        float side = player.getMainArm() == HumanoidArm.RIGHT ? 1f : -1f;

        // --- blend weight: 0 = resting in hand, 1 = full swing ---
        float bt = (blendTicks + pt) / (blendingIn ? BLEND_IN_TICKS : BLEND_OUT_TICKS);
        float w = Mth.lerp(smooth(bt), blendFrom, blendingIn ? 1f : 0f);
        lastW = w;

        // --- idle hold pose (with equip animation and real-time breathing) ---
        float breath = Mth.sin((Util.getMillis() / 1000f) * Mth.TWO_PI / BREATH_PERIOD_SECONDS);
        Vector3f restGrip = new Vector3f(REST_GRIP.x * side, REST_GRIP.y, REST_GRIP.z);
        Vector3f holdGrip = new Vector3f(HOLD_GRIP.x * side, HOLD_GRIP.y - lastEquip * 0.6f - breath * BREATH_MOVE, HOLD_GRIP.z);
        Pose idle = new Pose(holdGrip, breath * BREATH_TILT);

        // --- where the swing lands ---
        Vector3f raisedGrip = new Vector3f(RAISED_GRIP.x * side, RAISED_GRIP.y, RAISED_GRIP.z);
        Vector3f hitGrip = new Vector3f(restGrip).add(AIR_SWING_REACH);
        Vector3f aimDir = new Vector3f(REST_AIM);
        float lunge = 0f;
        if (aimPoint != null && w > 0.001f) {
            Vec3 cp = cam.getPosition();
            Vector3f v = new Vector3f((float) (aimPoint.x - cp.x), (float) (aimPoint.y - cp.y), (float) (aimPoint.z - cp.z));
            v.rotate(new Quaternionf(camRot).conjugate());
            float len = v.length();
            if (len > 1e-3f) v.mul((len + PENETRATION) / len);
            Vector3f toTarget = new Vector3f(v).sub(restGrip);
            float dist = toTarget.length();
            if (dist > 1e-3f) {
                toTarget.div(dist);
                lunge = Mth.clamp(dist - TIP_LEN, 0f, MAX_LUNGE);
                hitGrip = new Vector3f(restGrip).add(new Vector3f(toTarget).mul(lunge));
                aimDir = toTarget;
            }
        }

        // --- the swing ---
        float phase = ((swingClock + pt) / SWING_TICKS) % 1f;
        Pose pose = Pose.lerp(idle, cyclePose(phase, hitGrip, raisedGrip), w);

        // Hitting mobs / punching air when not mining: one quick chop
        if (w < 0.05f) {
            float a = player.getAttackAnim(pt);
            if (a > 0f) {
                Vector3f fwd = new Vector3f(restGrip).add(AIR_SWING_REACH);
                float p = COCK_END + (1f - COCK_END) * Math.min(1f, a * 1.6f);
                pose = Pose.lerp(pose, cyclePose(p, fwd, raisedGrip), Mth.sin(a * Mth.PI));
            }
        }

        // Physical recoil from the impact
        float s = Mth.lerp(pt, prevShake, shake);
        pose = new Pose(new Vector3f(pose.grip).add(0f, 0f, s * RECOIL_KICK), pose.pitch + s * RECOIL_PITCH);

        // --- aim (smoothed, frame-rate independent) ---
        Quaternionf restQ = holdQuat(side);
        Quaternionf goal = new Quaternionf(restQ).slerp(aimQuat(aimDir, side), w);
        if (!aimInit) {
            aimQ.set(goal);
            aimInit = true;
        } else {
            aimQ.slerp(goal, 1f - (float) Math.pow(0.0005, dt));
        }

        // --- build the rig ---
        Vector3f shoulder = new Vector3f(SHOULDER.x * side, SHOULDER.y, SHOULDER.z);
        PoseStack ps = new PoseStack();
        ps.mulPose(camRot); // view space -> camera-relative world

        // Inertia sway + walk bob, pivoting at the shoulder
        float xBob = Mth.lerp(pt, player.xBobO, player.xBob);
        float yBob = Mth.lerp(pt, player.yBobO, player.yBob);
        float lagPitch = Mth.clamp(player.getViewXRot(pt) - xBob, -SWAY_MAX_DEGREES, SWAY_MAX_DEGREES);
        float lagYaw = Mth.clamp(Mth.wrapDegrees(player.getViewYRot(pt) - yBob), -SWAY_MAX_DEGREES, SWAY_MAX_DEGREES);
        float walk = -(player.walkDist + (player.walkDist - player.walkDistO) * pt);
        float bob = Mth.lerp(pt, player.oBob, player.bob) * WALK_BOB * (1f - 0.6f * w);

        ps.translate(shoulder.x, shoulder.y, shoulder.z);
        ps.mulPose(Axis.XP.rotationDegrees(lagPitch * SWAY_STRENGTH + Math.abs(Mth.cos(walk * Mth.PI)) * bob * 5f));
        ps.mulPose(Axis.YP.rotationDegrees(lagYaw * SWAY_STRENGTH));
        ps.mulPose(Axis.ZP.rotationDegrees(Mth.sin(walk * Mth.PI) * bob * 3f * side));
        ps.translate(-shoulder.x, -shoulder.y, -shoulder.z);
        ps.translate(Mth.sin(walk * Mth.PI) * bob * 0.04f, -Math.abs(Mth.cos(walk * Mth.PI)) * bob * 0.05f, 0f);

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        int light = mc.getEntityRenderDispatcher().getPackedLightCoords(player, pt);

        // Arm: from the (leaning) shoulder to the hand on the handle
        if (SHOW_ARM) {
            Vector3f lean = new Vector3f(pose.grip).sub(restGrip).mul(BODY_LEAN * Math.min(1f, lunge / MAX_LUNGE + 0.0001f));
            Vector3f sh = new Vector3f(shoulder).add(lean);
            renderArm(mc, player, ps, buffers, light, sh, pose.grip, side);
        }

        // Pickaxe
        ps.pushPose();
        ps.translate(pose.grip.x, pose.grip.y, pose.grip.z);
        ps.mulPose(aimQ);
        ps.mulPose(Axis.XP.rotationDegrees(pose.pitch));
        ps.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE);
        ps.mulPose(Axis.YP.rotationDegrees(90f));
        ps.mulPose(Axis.ZP.rotationDegrees(45f));
        ps.translate(GRIP_OFFSET, GRIP_OFFSET, 0f);
        mc.getItemRenderer().renderStatic(player, lastHandStack, ItemDisplayContext.NONE, false, ps, buffers,
                mc.level, light, OverlayTexture.NO_OVERLAY, player.getId());
        ps.popPose();

        buffers.endBatch();
        rigDrawnThisFrame = true;
    }

    /**
     * Draws your body with the normal player renderer, so armor, held items, elytra and cape all
     * show up. The head (and your helmet) are skipped so they can't block your view, and the
     * pickaxe arm is skipped while the mining rig is drawing it.
     */
    private static void renderBody(Minecraft mc, LocalPlayer player, Camera cam, float pt,
                                   MultiBufferSource buffers, boolean hideMainArm) {
        net.minecraft.world.entity.Pose pose = player.getPose();
        if (pose != net.minecraft.world.entity.Pose.STANDING && pose != net.minecraft.world.entity.Pose.CROUCHING) {
            return; // swimming, gliding, sleeping, etc. look wrong from inside the body
        }
        EntityRenderer<? super LocalPlayer> r = mc.getEntityRenderDispatcher().getRenderer(player);
        if (!(r instanceof PlayerRenderer pr)) return;

        Vec3 pos = player.getPosition(pt);
        Vec3 cp = cam.getPosition();
        float bodyYaw = Mth.rotLerp(pt, player.yBodyRotO, player.yBodyRot);
        float yawRad = bodyYaw * Mth.DEG_TO_RAD;

        PoseStack ps = new PoseStack();
        ps.translate(pos.x - cp.x + Mth.sin(yawRad) * BODY_BACK_OFFSET,
                     pos.y - cp.y + (player.isCrouching() ? -0.125f : 0f),
                     pos.z - cp.z - Mth.cos(yawRad) * BODY_BACK_OFFSET);
        int light = mc.getEntityRenderDispatcher().getPackedLightCoords(player, pt);

        // For this one draw only: no helmet (it would cover the camera) and, if the rig is
        // showing the pickaxe, no second pickaxe in the body's hand. Restored right after.
        Inventory inv = player.getInventory();
        int headSlot = EquipmentSlot.HEAD.getIndex();
        ItemStack savedHelmet = inv.armor.get(headSlot);
        int sel = inv.selected;
        boolean swapMain = hideMainArm && Inventory.isHotbarSlot(sel);
        ItemStack savedMain = swapMain ? inv.items.get(sel) : ItemStack.EMPTY;

        bodyHideMainArm = hideMainArm;
        drawingFirstPersonBody = true;
        try {
            inv.armor.set(headSlot, ItemStack.EMPTY);
            if (swapMain) inv.items.set(sel, ItemStack.EMPTY);
            pr.render(player, bodyYaw, pt, ps, buffers, light);
        } finally {
            inv.armor.set(headSlot, savedHelmet);
            if (swapMain) inv.items.set(sel, savedMain);
            drawingFirstPersonBody = false;
        }
    }

    /** Runs inside the player renderer, after it sets up part visibility: hide head and pickaxe arm. */
    @SubscribeEvent
    public static void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        if (!drawingFirstPersonBody) return;
        PlayerModel<AbstractClientPlayer> model = event.getRenderer().getModel();
        model.head.visible = false;
        model.hat.visible = false;
        if (bodyHideMainArm) {
            boolean right = event.getEntity().getMainArm() == HumanoidArm.RIGHT;
            (right ? model.rightArm : model.leftArm).visible = false;
            (right ? model.rightSleeve : model.leftSleeve).visible = false;
        }
    }

    private static void renderArm(Minecraft mc, LocalPlayer player, PoseStack ps, MultiBufferSource buffers,
                                  int light, Vector3f shoulder, Vector3f hand, float side) {
        EntityRenderer<? super LocalPlayer> r = mc.getEntityRenderDispatcher().getRenderer(player);
        if (!(r instanceof PlayerRenderer pr)) return;
        PlayerModel<AbstractClientPlayer> model = pr.getModel();
        boolean right = side > 0f;
        ModelPart arm = right ? model.rightArm : model.leftArm;
        ModelPart sleeve = right ? model.rightSleeve : model.leftSleeve;
        PlayerSkin skin = player.getSkin();
        boolean slim = skin.model() == PlayerSkin.Model.SLIM;
        float centerX = (slim ? 0.5f : 1f) * (right ? 1f : -1f);

        Vector3f dir = new Vector3f(hand).sub(shoulder);
        float dist = dir.length();
        if (dist < 1e-3f) return;
        dir.div(dist);
        float stretch = Mth.clamp(dist / ARM_LEN, 1f, ARM_STRETCH_MAX);
        Vector3f start = new Vector3f(hand).sub(new Vector3f(dir).mul(ARM_LEN * stretch));

        PartPose armPose = arm.storePose();
        PartPose sleevePose = sleeve.storePose();
        boolean sleeveVisible = sleeve.visible;
        try {
            arm.setPos(centerX, 0f, 0f);
            arm.setRotation(0f, 0f, 0f);
            sleeve.setPos(centerX, 0f, 0f);
            sleeve.setRotation(0f, 0f, 0f);
            sleeve.visible = player.isModelPartShown(right ? PlayerModelPart.RIGHT_SLEEVE : PlayerModelPart.LEFT_SLEEVE);

            ps.pushPose();
            ps.translate(start.x, start.y, start.z);
            ps.mulPose(new Quaternionf().rotationTo(0f, -1f, 0f, dir.x, dir.y, dir.z));
            ps.scale(1f, stretch, 1f);
            ps.mulPose(Axis.ZP.rotationDegrees(180f)); // model space is y-down
            arm.render(ps, buffers.getBuffer(RenderType.entitySolid(skin.texture())), light, OverlayTexture.NO_OVERLAY);
            sleeve.render(ps, buffers.getBuffer(RenderType.entityTranslucent(skin.texture())), light, OverlayTexture.NO_OVERLAY);
            ps.popPose();
        } finally {
            arm.loadPose(armPose);
            sleeve.loadPose(sleevePose);
            sleeve.visible = sleeveVisible;
        }
    }

    /** Orientation for the idle hold: handle along HOLD_HANDLE, point toward HOLD_TIP. */
    private static Quaternionf holdQuat(float side) {
        Vector3f y = new Vector3f(HOLD_HANDLE.x * side, HOLD_HANDLE.y, HOLD_HANDLE.z).normalize();
        Vector3f f = new Vector3f(HOLD_TIP.x * side, HOLD_TIP.y, HOLD_TIP.z);
        f.sub(new Vector3f(y).mul(f.dot(y))).normalize();   // make the point direction square to the handle
        Vector3f z = new Vector3f(f).negate();              // sprite's local -Z is the point direction
        Vector3f x = new Vector3f(y).cross(z).normalize();
        Matrix3f m = new Matrix3f(x.x, x.y, x.z, y.x, y.y, y.z, z.x, z.y, z.z);
        return new Quaternionf().setFromNormalized(m);
    }

    private static Quaternionf aimQuat(Vector3f dir, float side) {
        Quaternionf q = new Quaternionf().rotationTo(TIP_DIR, dir);
        return new Quaternionf().rotationAxis((float) Math.toRadians(WRIST_ROLL * side), dir.x, dir.y, dir.z).mul(q);
    }

    private static Pose cyclePose(float p, Vector3f hitGrip, Vector3f raisedGrip) {
        Pose impact = new Pose(hitGrip, FOLLOW_PITCH);
        Pose raised = new Pose(raisedGrip, RAISED_PITCH);
        Pose cocked = new Pose(raisedGrip, COCK_PITCH);
        if (p < HOLD_END) return impact;
        if (p < RECOIL_END) return Pose.lerp(impact, raised, easeOutBack((p - HOLD_END) / (RECOIL_END - HOLD_END)));
        if (p < COCK_END) return Pose.lerp(raised, cocked, smooth((p - RECOIL_END) / (COCK_END - RECOIL_END)));
        return Pose.lerp(cocked, impact, easeInCubic((p - COCK_END) / (1f - COCK_END)));
    }

    private static float easeInCubic(float t) { return t * t * t; }
    private static float easeOutBack(float t) { float c = 1.2f, u = t - 1f; return 1f + (c + 1f) * u * u * u + c * u * u; }
    private static float smooth(float t) { t = Mth.clamp(t, 0f, 1f); return t * t * (3f - 2f * t); }

    // ================================================================
    // CAMERA: gentle dip only, no roll
    // ================================================================
    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (CAMERA_SHAKE <= 0f) return;
        float s = Mth.lerp((float) event.getPartialTick(), prevShake, shake);
        if (s > 0.001f) event.setPitch(event.getPitch() + s * CAMERA_SHAKE);
    }

    // ================================================================
    private record Pose(Vector3f grip, float pitch) {
        static Pose lerp(Pose a, Pose b, float t) {
            return new Pose(new Vector3f(a.grip).lerp(b.grip, t), Mth.lerp(t, a.pitch, b.pitch));
        }
    }
}
