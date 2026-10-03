package com.example.heavymining.client;

import com.example.heavymining.HeavyMining;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

@EventBusSubscriber(modid = HeavyMining.MOD_ID, value = Dist.CLIENT)
public final class MiningAnimator {

    // ------------------------------------------------------------------
    // TUNING - tweak these to change the feel
    // ------------------------------------------------------------------

    /** Ideal length of one swing in ticks (20 ticks = 1 s). The video is ~0.5 s per hit. */
    private static final float TARGET_SWING_TICKS = 10f;
    /** Never swing faster than this, even on instant-break blocks. */
    private static final float MIN_SWING_TICKS = 4f;

    /** Swing cycle layout (0..1). Impact happens at 1.0 (== 0.0 of the next swing). */
    private static final float SETTLE_END = 0.15f;   // 0.00-0.15 recoil from impact back to rest
    private static final float WINDUP_END = 0.62f;   // 0.15-0.62 slow raise (wind-up)
                                                     // 0.62-1.00 fast accelerating strike
    /** Ticks to ease back to the normal hold after you stop mining. */
    private static final float RELEASE_TICKS = 5f;

    /** Screen shake strength per hit (0 disables). Also scaled by the "Screen Effects" option. */
    private static final float SHAKE_PITCH = 1.4f;
    private static final float SHAKE_ROLL = 0.8f;
    /** Extra crack particles burst out of the block on each hit. */
    private static final int IMPACT_PARTICLES = 6;

    // Key poses, relative to the vanilla first-person hold.
    // translate x/y/z (blocks), rotate x/y/z (degrees). Negative X-rotation swings the head forward/down.
    private static final Pose REST   = new Pose(0f,     0f,     0f,      0f,   0f,   0f);
    private static final Pose RAISED = new Pose(0.10f,  0.20f,  0.12f,  38f, -12f,  12f);
    private static final Pose IMPACT = new Pose(-0.14f, -0.10f, -0.28f, -62f,  8f,  -6f);

    // ------------------------------------------------------------------
    // STATE
    // ------------------------------------------------------------------
    private static boolean active;
    private static BlockPos target;
    private static Direction targetFace = Direction.UP;
    private static int ticksMining;
    private static float period = TARGET_SWING_TICKS;
    private static int impactsDone;

    private static Pose lastPose = REST;
    private static int releaseTicks = -1; // >= 0 while easing back to rest

    private static float shake, prevShake;

    private MiningAnimator() {}

    // ------------------------------------------------------------------
    // TICK: track mining, sync swing speed to break time, fire impacts
    // ------------------------------------------------------------------
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        prevShake = shake;
        shake *= 0.55f;

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
                // Block just broke? Give the final hit its impact.
                if (target != null && mc.level.getBlockState(target).isAir()) {
                    onImpact(mc, null, null);
                }
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

        int impacts = (int) (ticksMining / period);
        if (impacts > impactsDone) {
            impactsDone = impacts;
            onImpact(mc, pos, targetFace);
        }
    }

    private static void startBlock(Minecraft mc, LocalPlayer player, BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        float perTick = state.getDestroyProgress(player, mc.level, pos);
        float totalTicks = perTick <= 0f ? 1_000_000f : (float) Math.ceil(1f / perTick);

        // Fit a whole number of swings into the break time so the LAST impact
        // lands on the tick the block breaks.
        int swings = Math.max(1, Math.round(totalTicks / TARGET_SWING_TICKS));
        period = Math.max(MIN_SWING_TICKS, totalTicks / swings);

        target = pos.immutable();
        ticksMining = 0;
        impactsDone = 0;
        active = true;
        releaseTicks = -1;
    }

    private static void onImpact(Minecraft mc, BlockPos pos, Direction face) {
        float effects = mc.options.screenEffectScale().get().floatValue();
        shake = Math.max(shake, effects);
        if (pos != null && face != null) {
            for (int i = 0; i < IMPACT_PARTICLES; i++) {
                mc.particleEngine.crack(pos, face);
            }
        }
    }

    // ------------------------------------------------------------------
    // SWING CURVE
    // ------------------------------------------------------------------
    private static float phase(float partialTick) {
        float e = ticksMining + partialTick;
        if (e < period) {
            // First swing starts from rest, not from the impact pose.
            return SETTLE_END + (1f - SETTLE_END) * (e / period);
        }
        return (e / period) % 1f;
    }

    private static Pose poseAt(float p) {
        if (p < SETTLE_END) {
            return Pose.lerp(IMPACT, REST, easeOutBack(p / SETTLE_END));
        } else if (p < WINDUP_END) {
            return Pose.lerp(REST, RAISED, easeInOut((p - SETTLE_END) / (WINDUP_END - SETTLE_END)));
        } else {
            return Pose.lerp(RAISED, IMPACT, easeInCubic((p - WINDUP_END) / (1f - WINDUP_END)));
        }
    }

    private static float easeInCubic(float t) { return t * t * t; }                       // heavy acceleration into the hit
    private static float easeInOut(float t)   { return t * t * (3f - 2f * t); }          // smooth lift
    private static float easeOutBack(float t) {                                          // slight bounce on recoil
        float c = 1.4f;
        float u = t - 1f;
        return 1f + (c + 1f) * u * u * u + c * u * u;
    }

    // ------------------------------------------------------------------
    // RENDER: draw the pickaxe ourselves while animating
    // ------------------------------------------------------------------
    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        ItemStack stack = event.getItemStack();
        if (!stack.is(ItemTags.PICKAXES)) return;

        float pt = event.getPartialTick();
        Pose pose;
        if (active) {
            pose = poseAt(phase(pt));
            lastPose = pose;
        } else if (releaseTicks >= 0) {
            float t = Math.min(1f, (releaseTicks + pt) / RELEASE_TICKS);
            pose = Pose.lerp(lastPose, REST, easeInOut(t));
        } else {
            return; // not mining: let vanilla render normally
        }

        event.setCanceled(true);

        Minecraft mc = Minecraft.getInstance();
        boolean rightHanded = mc.player.getMainArm() == HumanoidArm.RIGHT;
        float side = rightHanded ? 1f : -1f;

        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        // Vanilla first-person hold position
        ps.translate(side * 0.56f, -0.52f + event.getEquipProgress() * -0.6f, -0.72f);
        // Our animation, pivoting around the grip
        ps.translate(side * pose.tx(), pose.ty(), pose.tz());
        ps.mulPose(Axis.YP.rotationDegrees(side * pose.ry()));
        ps.mulPose(Axis.ZP.rotationDegrees(side * pose.rz()));
        ps.mulPose(Axis.XP.rotationDegrees(pose.rx()));

        mc.getEntityRenderDispatcher().getItemInHandRenderer().renderItem(
                mc.player, stack,
                rightHanded ? ItemDisplayContext.FIRST_PERSON_RIGHT_HAND : ItemDisplayContext.FIRST_PERSON_LEFT_HAND,
                !rightHanded, ps, event.getMultiBufferSource(), event.getPackedLight());
        ps.popPose();
    }

    // ------------------------------------------------------------------
    // CAMERA: small jolt on each impact
    // ------------------------------------------------------------------
    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        float pt = (float) event.getPartialTick();
        float s = Mth.lerp(pt, prevShake, shake);
        if (s < 0.001f) return;
        float t = Minecraft.getInstance().player.tickCount + pt;
        event.setPitch(event.getPitch() + s * SHAKE_PITCH);
        event.setRoll(event.getRoll() + s * SHAKE_ROLL * Mth.sin(t * 3.1f));
    }

    // ------------------------------------------------------------------
    private record Pose(float tx, float ty, float tz, float rx, float ry, float rz) {
        static Pose lerp(Pose a, Pose b, float t) {
            return new Pose(
                    Mth.lerp(t, a.tx, b.tx), Mth.lerp(t, a.ty, b.ty), Mth.lerp(t, a.tz, b.tz),
                    Mth.lerp(t, a.rx, b.rx), Mth.lerp(t, a.ry, b.ry), Mth.lerp(t, a.rz, b.rz));
        }
    }
}
