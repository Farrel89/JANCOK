$input v_clipPosition, v_color0, v_fog, v_light, v_texcoord0, v_worldPos

#include <bgfx_shader.sh>
#include <MinecraftRenderer.Materials/ActorUtil.dragonh>
#include <MinecraftRenderer.Materials/FogUtil.dragonh>
#include <MinecraftRenderer.Materials/DitherUtil.dragonh>

uniform vec4 FogControl;

#define ACTOR_APA 0.6
#define ACTOR_AO 1
#define ACTOR_AO_MIN 0.55
#define ACTOR_AO_MAX 0.8
#define END_BRIGHT   4.0
#define END_SAT      0.7
#define END_LM_TINT  0.35
#define END_SHOULDER 0.7
#define END_DIM      0.55

bool endFog(vec4 f) {
    return (f.r > f.g && f.b > f.g && f.r < 0.05 && f.b < 0.05 && f.g < 0.05);
}
vec3 ACES(vec3 x) {
    return (x * (3.0 * x + 0.001)) / (x * (3.1 * x + 0.48) + 0.1);
}
vec3 Tm(vec3 x) {
    return (x * (4.1 * x + 0.01)) / (x * (2.6 * x + 0.56) + 0.4);
}
vec3 actorSaturation(vec3 rgb, float adj) {
    vec3 W = vec3(0.2125, 0.7154, 0.0721);
    return mix(vec3_splat(dot(rgb, W)), rgb, adj);
}

vec3 actorGrade(vec3 c) {
    vec4 fctl = FogControl;
    if (fctl.z < 1.0) { return c; }
    vec4 fclr = v_fog;
    vec3 ks = v_clipPosition.xyz;
    bool akhir = endFog(fclr);
    bool udw = (fctl.x < 0.01) && !akhir;
    float endK = (fctl.x < 0.01) ? END_DIM : 1.0;
    bool nether = (fclr.r > fclr.b && fclr.r < 0.5 && fclr.b < 0.2);

#if ACTOR_AO
    if (akhir) {
        c *= ACTOR_AO_MAX;
    } else {
        float lum = dot(v_light.xyz, vec3_splat(0.3333));
        c *= mix(ACTOR_AO_MIN, ACTOR_AO_MAX, smoothstep(0.3, 0.7, lum));
    }
#endif

    vec3 cr = cross(dFdx(v_worldPos), dFdy(v_worldPos));
    vec3 N = cr / max(length(cr), 0.000001);
    float fogL = dot(fclr.rgb, vec3(0.2125, 0.7154, 0.0721));
    float wk = smoothstep(0.08, 0.4, fogL);
    float uvy = smoothstep(0.25, 0.55, dot(v_light.xyz, vec3_splat(0.3333)));
    float malamd = 1.0 - wk * uvy;
    vec2 rf = vec2(0.5 + 20.0 / fctl.z, 1.0);
    vec2 rt = clamp((fctl.xy - rf) / (vec2(0.23, 0.7) - rf), 0.0, 1.0);
    float rk = rt.x * rt.y;
    float hujand = rk * rk * (3.0 - 2.0 * rk);
    float dir = max(N.x, -N.z);
    c *= mix(vec3_splat(0.75), vec3_splat(0.65), dir * uvy * (1.0 - hujand) * (1.0 - malamd));

    if (!akhir) {
        c *= vec3_splat(1.7);
        c = actorSaturation(c, 1.2);
        if (!udw) { c *= ACES(c); }
        c = Tm(c);
    }
    if (akhir) {
        vec3 ec = c * END_BRIGHT * endK;
        ec = actorSaturation(ec, END_SAT);
        vec3 eo = max(ec - vec3_splat(END_SHOULDER), vec3_splat(0.0));
        float ek = 1.0 - END_SHOULDER;
        ec = min(ec, vec3_splat(END_SHOULDER)) + ek * (vec3_splat(1.0) - exp(-eo / ek));
        c = ec;
        float drl = max(-N.x, -N.z);
        c *= mix(vec3_splat(0.85), vec3_splat(0.65), drl);
    }
    if (udw) {
        c *= vec3(0.3, 1.1, 0.85);
        c = mix(c, fclr.xyz * sqrt(vec3(0.8, 1.5, 1.4)), ACTOR_APA * clamp(length(-ks) / 50.0, 0.0, 1.0));
        c *= ACES(c);
    }
    if (nether) {
        c *= vec3(1.5, 0.9, 0.0);
        c += vec3_splat(0.2);
        c = Tm(c) * 0.9;
        c = mix(c, fclr.rgb * vec3(1.5, 1.0, 0.0), clamp(length(-ks) / fctl.z * 1.5, 0.1, 0.2));
    }
    return c;
}

vec3 actorHaze(vec3 c) {
    vec4 fctl = FogControl;
    if (fctl.z < 1.0) { return c; }
    if (endFog(v_fog)) { return c; }
    vec3 ks = v_clipPosition.xyz;
    vec2 rf = vec2(0.5 + 20.0 / fctl.z, 1.0);
    vec2 rt = clamp((fctl.xy - rf) / (vec2(0.23, 0.7) - rf), 0.0, 1.0);
    float rk = rt.x * rt.y;
    float hujand = rk * rk * (3.0 - 2.0 * rk);
    vec3 h = mix(c, v_fog.xyz * sqrt(vec3_splat(1.1)), ACTOR_APA * clamp(length(-ks) / 100.0, 0.0, 1.0));
    return mix(c, h, 1.0 - hujand);
}

vec3 actorLight() {
    vec3 l = v_light.xyz;
    if (FogControl.z >= 1.0 && endFog(v_fog)) {
        float ll = dot(l, vec3(0.2125, 0.7154, 0.0721));
        l = mix(vec3_splat(ll), l, END_LM_TINT);
    }
    return l;
}

void main() {
#if defined(DEPTH_ONLY) || defined(DEPTH_ONLY_OPAQUE) || defined(DEPTH_ONLY_ALPHA_TEST)
    gl_FragColor = vec4_splat(0.0);
    return;
#else

    vec4 albedo = getActorAlbedoNoColorChange(v_texcoord0, s_MatTexture, s_MatTexture1, MatColor);

    bool ditherDiscard = false;
    if (DitheringEnabledToggle.x != 0.0) {
        vec3 camPos = mul(u_invView, vec4(0.0, 0.0, 0.0, 1.0)).xyz;
        ditherDiscard = evaluateDitherDiscard(v_clipPosition, v_worldPos, camPos, u_view, DitherParams, DitherParams2[0]);
    }

#if defined(ALPHA_TEST)
    float alpha = mix(albedo.a, albedo.a * OverlayColor.a, TintedAlphaTestEnabled.x);
    if (ditherDiscard || shouldDiscard(albedo.rgb, alpha, ActorFPEpsilon.x)) {
        discard;
    }
#endif

#if defined(CHANGE_COLOR_MULTI)
    albedo = applyMultiColorChange(albedo, ChangeColor.rgb, MultiplicativeTintColor.rgb);
#elif defined(CHANGE_COLOR)
    albedo = applyColorChange(albedo, ChangeColor, albedo.a);
    albedo.a *= ChangeColor.a;
#endif

#if defined(ALPHA_TEST)
    albedo.a = max(UseAlphaRewrite.x, albedo.a);
#endif

    albedo = applyActorDiffuse(albedo, v_color0.rgb, vec4(actorLight(), v_light.a), ColorBased.x, OverlayColor); // <-- UBAH

#if defined(TRANSPARENT)
    albedo = applyHudOpacity(albedo, HudOpacity.x);
    if (ditherDiscard) {
        albedo.a = 0.0;
    }
#endif

    albedo.rgb = applyFog(actorGrade(albedo.rgb), v_fog.rgb, v_fog.a); // <-- UBAH
    albedo.rgb = actorHaze(albedo.rgb);                                // <-- UBAH (baris baru)
    gl_FragColor = albedo;
#endif
}
