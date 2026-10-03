package com.example.heavymining.client;

import com.example.heavymining.HeavyMining;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
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
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Heavy Mining v2 - physical, hit-based mining.
 *
 *  - The pickaxe is drawn IN THE WORLD (not on top of the screen), so on impact its head
 *    really sinks into the block surface and gets hidden by it.
 *  - The swing aims at the exact point under your crosshair, and your arm lunges toward it.
 *  - Mining is split into discrete hits: cracks only grow ON a hit, and the block breaks
 *    exactly on the final hit (about 5 hits per second, like the reference video).
 *  - Each impact: brief hit-stop, camera jolt, chips flying off the exact hit point, hit sound.
 *
 * Mining is never faster than vanilla, so it is safe on servers.
 */
@EventBusSubscriber(modid = HeavyMining.MOD_ID, value = Dist.CLIENT)
public final class MiningAnimator {

    // ================================================================
    // TUNING
    // ================================================================

    /** Ticks per swing (20 ticks = 1 s). 4 = 5 hits/second, matching the reference. */
    private static final int SWING_TICKS = 4;
    /** More = more hits per block. Diamond pickaxe on stone gives about 6 hits. */
    private static final float HITS_SCALE = 2.4f;

    /** Size of the pickaxe in the world, in blocks. */
    private static final float ITEM_SCALE = 0.75f;
    /** How far your arm may reach forward toward the block (blocks). Get close for real contact! */
    private static final float MAX_LUNGE = 0.55f;
    /** How deep the head sinks into the block on impact (blocks). */
    private static final float PENETRATION = 0.06f;
    /** Wrist tilt in degrees. */
    private static final float WRIST_ROLL = -12f;

    // Swing angles (degrees, around the wrist; positive = head back over your shoulder)
    private static final float RAISED_PITCH = 100f;  // top of the backswing
    private static final float COCK_PITCH = 108f;    // tiny extra wind-up before the strike
    private static final float FOLLOW_PITCH = -6f;   // slight follow-through into the block
    private static final float REST_PITCH = 20f;

    // Hand positions in view space (x = right, y = up, -z = forward), in blocks
    private static final Vector3f REST_GRIP = new Vector3f(0.30f, -0.36f, -0.40f);
    private static final Vector3f RAISED_GRIP = new Vector3f(0.40f, -0.14f, -0.22f);
    private static final Vector3f REST_AIM = new Vector3f(0f, 0.35f, -1f).normalize();

    // Swing cycle layout (0..1); impact happens at 1.0 == 0.0
    private static final float HOLD_END = 0.10f;    // hit-stop: head stays buried
    private static final float RECOIL_END = 0.34f;  // snap back up and over the shoulder
    private static final float COCK_END = 0.46f;    // short wind-up
                                                    // 0.46 - 1.00: accelerating strike
    private static final float ENTER_TICKS = 1.5f;
    private static final float RELEASE_TICKS = 4f;

    private static final float SHAKE_PITCH = 1.1f;
    private static final float SHAKE_ROLL = 0.5f;
    private static final int IMPACT_PARTICLES = 7;

    // Pickaxe sprite geometry, measured from the vanilla texture:
    // grip near the bottom of the handle, striking tip at the lower end of the head.
    private static final float GRIP_OFFSET = 0.33f;
    private static final Vector3f TIP_DIR = new Vector3f(0f, 0.47f, -0.48f).normalize();
    private static final float TIP_LEN = 0.67f * ITEM_SCALE;

    // ================================================================
    // STATE
    // ================================================================
    private static boolean active;
    private static boolean managed;          // false for insta-mine / unbreakable blocks
    private static boolean computing;        // guard while asking vanilla for the break speed
    private static BlockPos target;
    private static BlockState targetState;
    private static Direction targetFace = Direction.UP;
    private static Vec3 targetPoint;
    private static int ticksMining;
    private static float vanillaPerTick;
    private static int hitsNeeded = 1;
    private static int hitsDone;
    private static boolean impactThisTick;

    private static Pose lastPose;
    private static Pose enterFrom;
    private static int releaseTicks = -1;
    private static final Quaternionf aimQ = new Quaternionf();
    private static boolean aimInit;

    private static float shake, prevShake;
    private static boolean playingOwnSound;

    private MiningAnimator() {}

    // ================================================================
    // TICK
    // ================================================================
    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        prevShake = shake;
        shake *= 0.5f;
        impactThisTick = false;

        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) {
            active = false;
            releaseTicks = -1;
            return;
        }

        BlockHitResult hit = (mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK) ? b : null;
        boolean mining = hit != null
                && mc.gameMode.isDestroying()
                && player.getMainHandItem().is(ItemTags.PICKAXES);

        if (!mining) {
            if (active) {
                active = false;
                releaseTicks = 0;
            } else if (releaseTicks >= 0 && ++releaseTicks > RELEASE_TICKS) {
                releaseTicks = -1;
            }
            return;
        }

        BlockPos pos = hit.getBlockPos();
        if (!active || !pos.equals(target)) {
            startBlock(mc, player, pos);
        } else {
            ticksMining++;
        }
        targetFace = hit.getDirection();
        targetPoint = hit.getLocation();

        if (ticksMining > 0 && ticksMining % SWING_TICKS == 0) {
            impactThisTick = true;
            hitsDone++;
            impactFx(mc);
        }
    }

    private static void startBlock(Minecraft mc, LocalPlayer player, BlockPos pos) {
        enterFrom = (active || releaseTicks >= 0) && lastPose != null ? lastPose : null;
        targetState = mc.level.getBlockState(pos);

        computing = true;
        try {
            vanillaPerTick = targetState.getDestroyProgress(player, mc.level, pos);
        } finally {
            computing = false;
        }
        managed = vanillaPerTick > 0f && vanillaPerTick < 1f;
        if (managed) {
            int vanillaTicks = (int) Math.ceil(1f / vanillaPerTick);
            int minHits = (int) Math.ceil(vanillaTicks / (float) SWING_TICKS); // never faster than vanilla
            hitsNeeded = Math.max(1, Math.max(minHits, Math.round((float) Math.sqrt(vanillaTicks) * HITS_SCALE)));
        } else {
            hitsNeeded = 1;
        }

        target = pos.immutable();
        ticksMining = 0;
        hitsDone = 0;
        active = true;
        releaseTicks = -1;
    }

    // ================================================================
    // BREAK SPEED: cracks only advance on a hit, block breaks on the last hit
    // ================================================================
    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        if (computing) return;
        Player p = event.getEntity();
        if (!p.level().isClientSide()) return;               // never touch the server's side
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

        // Not tracked yet (first tick on a new block): hold progress unless it's an insta-mine.
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
        if (targetPoint == null || targetState == null || targetState.isAir()) return;

        RandomSource r = mc.level.random;
        BlockParticleOption chips = new BlockParticleOption(ParticleTypes.BLOCK, targetState);
        double nx = targetFace.getStepX(), ny = targetFace.getStepY(), nz = targetFace.getStepZ();
        for (int i = 0; i < IMPACT_PARTICLES; i++) {
            mc.level.addParticle(chips,
                    targetPoint.x + nx * 0.03, targetPoint.y + ny * 0.03, targetPoint.z + nz * 0.03,
                    nx * 0.10 + (r.nextDouble() - 0.5) * 0.15,
                    ny * 0.10 + r.nextDouble() * 0.10,
                    nz * 0.10 + (r.nextDouble() - 0.5) * 0.15);
        }

        SoundType sound = targetState.getSoundType();
        playingOwnSound = true;
        try {
            mc.level.playLocalSound(targetPoint.x, targetPoint.y, targetPoint.z, sound.getHitSound(),
                    SoundSource.BLOCKS, (sound.getVolume() + 1f) / 5f, sound.getPitch() * 0.6f, false);
        } finally {
            playingOwnSound = false;
        }
    }

    /** Mute vanilla's every-4-ticks hit sound so the only hit sound is ours, on the impact. */
    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        if (playingOwnSound || !active || targetState == null || event.getSound() == null) return;
        if (event.getSound().getSource() != SoundSource.BLOCKS) return;
        if (event.getSound().getLocation().equals(targetState.getSoundType().getHitSound().getLocation())) {
            event.setSound(null);
        }
    }

    // ================================================================
    // RENDER: hide the normal hand pickaxe, draw ours in the world
    // ================================================================
    private static boolean animating(Minecraft mc) {
        return (active || releaseTicks >= 0)
                && mc.player != null
                && mc.options.getCameraType().isFirstPerson()
                && mc.player.getMainHandItem().is(ItemTags.PICKAXES);
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (event.getHand() == InteractionHand.MAIN_HAND && animating(Minecraft.getInstance())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        Minecraft mc = Minecraft.getInstance();
        if (!animating(mc)) return;

        LocalPlayer player = mc.player;
        ItemStack stack = player.getMainHandItem();
        float pt = mc.getTimer().getGameTimeDeltaPartialTick(false);
        Camera cam = event.getCamera();
        Quaternionf camRot = new Quaternionf(cam.rotation());
        float side = player.getMainArm() == HumanoidArm.RIGHT ? 1f : -1f;

        Vector3f restGrip = new Vector3f(REST_GRIP.x * side, REST_GRIP.y, REST_GRIP.z);
        Vector3f raisedGrip = new Vector3f(RAISED_GRIP.x * side, RAISED_GRIP.y, RAISED_GRIP.z);

        // Where should the swing land? Turn the crosshair hit point into view space.
        Vector3f aimDir = new Vector3f(REST_AIM);
        Vector3f hitGrip = new Vector3f(restGrip);
        if (active && targetPoint != null) {
            Vec3 cp = cam.getPosition();
            Vector3f v = new Vector3f((float) (targetPoint.x - cp.x), (float) (targetPoint.y - cp.y), (float) (targetPoint.z - cp.z));
            v.rotate(new Quaternionf(camRot).conjugate());
            float len = v.length();
            if (len > 1e-3f) v.mul((len + PENETRATION) / len);

            Vector3f toTarget = new Vector3f(v).sub(restGrip);
            float dist = toTarget.length();
            if (dist > 1e-3f) {
                toTarget.div(dist);
                float lunge = Mth.clamp(dist - TIP_LEN, 0f, MAX_LUNGE);
                hitGrip = new Vector3f(restGrip).add(new Vector3f(toTarget).mul(lunge));
                aimDir = toTarget;
            }
        }

        Quaternionf aimGoal = new Quaternionf().rotationTo(TIP_DIR, aimDir);
        aimGoal = new Quaternionf().rotationAxis((float) Math.toRadians(WRIST_ROLL * side), aimDir.x, aimDir.y, aimDir.z).mul(aimGoal);
        if (!aimInit) {
            aimQ.set(aimGoal);
            aimInit = true;
        } else {
            aimQ.slerp(aimGoal, 0.35f);
        }

        Pose rest = new Pose(restGrip, REST_PITCH);
        Pose pose;
        if (active) {
            float e = ticksMining + pt;
            float p = e < SWING_TICKS
                    ? RECOIL_END + (1f - RECOIL_END) * (e / SWING_TICKS)  // first swing starts raised
                    : (e / SWING_TICKS) % 1f;
            pose = cyclePose(p, hitGrip, raisedGrip);
            if (e < ENTER_TICKS) {
                pose = Pose.lerp(enterFrom != null ? enterFrom : rest, pose, smooth(e / ENTER_TICKS));
            }
            lastPose = pose;
        } else {
            float t = Math.min(1f, (releaseTicks + pt) / RELEASE_TICKS);
            pose = Pose.lerp(lastPose != null ? lastPose : rest, rest, smooth(t));
        }

        PoseStack ps = new PoseStack();
        ps.mulPose(camRot);                       // view space -> world (camera-relative)
        ps.translate(pose.grip.x, pose.grip.y, pose.grip.z);
        ps.mulPose(aimQ);                         // point the striking tip at the target
        ps.mulPose(Axis.XP.rotationDegrees(pose.pitch)); // the swing itself
        ps.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE);
        ps.mulPose(Axis.YP.rotationDegrees(90f));  // turn the flat sprite so the head points forward
        ps.mulPose(Axis.ZP.rotationDegrees(45f));  // handle straight up
        ps.translate(GRIP_OFFSET, GRIP_OFFSET, 0f); // hold it by the handle

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        int light = mc.getEntityRenderDispatcher().getPackedLightCoords(player, pt);
        mc.getItemRenderer().renderStatic(player, stack, ItemDisplayContext.NONE, false, ps, buffers,
                mc.level, light, OverlayTexture.NO_OVERLAY, player.getId());
        buffers.endBatch();
    }

    private static Pose cyclePose(float p, Vector3f hitGrip, Vector3f raisedGrip) {
        Pose impact = new Pose(hitGrip, FOLLOW_PITCH);
        Pose raised = new Pose(raisedGrip, RAISED_PITCH);
        Pose cocked = new Pose(raisedGrip, COCK_PITCH);
        if (p < HOLD_END) return impact;
        if (p < RECOIL_END) return Pose.lerp(impact, raised, easeOutCubic((p - HOLD_END) / (RECOIL_END - HOLD_END)));
        if (p < COCK_END) return Pose.lerp(raised, cocked, smooth((p - RECOIL_END) / (COCK_END - RECOIL_END)));
        return Pose.lerp(cocked, impact, easeInCubic((p - COCK_END) / (1f - COCK_END)));
    }

    private static float easeInCubic(float t) { return t * t * t; }
    private static float easeOutCubic(float t) { float u = 1f - t; return 1f - u * u * u; }
    private static float smooth(float t) { t = Mth.clamp(t, 0f, 1f); return t * t * (3f - 2f * t); }

    // ================================================================
    // CAMERA JOLT
    // ================================================================
    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        float pt = (float) event.getPartialTick();
        float s = Mth.lerp(pt, prevShake, shake);
        if (s < 0.001f || Minecraft.getInstance().player == null) return;
        float t = Minecraft.getInstance().player.tickCount + pt;
        event.setPitch(event.getPitch() + s * SHAKE_PITCH);
        event.setRoll(event.getRoll() + s * SHAKE_ROLL * Mth.sin(t * 3.1f));
    }

    // ================================================================
    private record Pose(Vector3f grip, float pitch) {
        static Pose lerp(Pose a, Pose b, float t) {
            return new Pose(new Vector3f(a.grip).lerp(b.grip, t), Mth.lerp(t, a.pitch, b.pitch));
        }
    }
}
