$input v_clipPosition, v_worldPosition, v_color0, v_ditheringAndMaskTinting, v_fog, v_lightmapUV, v_texcoord0, v_worldPos
$input p, wp, ks

#include <bgfx_shader.sh>
#include <MinecraftRenderer.Materials/DitherUtil.dragonh>

uniform vec4 FogColor;
uniform vec4 FogAndDistanceControl;
uniform vec4 ViewPositionAndTime;
uniform vec4 DitherParams;
uniform vec4 DitherParams2[3];

SAMPLER2D_AUTOREG(s_MatTexture);
SAMPLER2D_AUTOREG(s_LightMapTexture);

#define TIME ViewPositionAndTime.w
#define saturate(x) clamp(x, 0.0, 1.0)

// ===== warna terrain dimensi End =====
#define END_BRIGHT     4.0
#define END_SAT        0.7
#define END_LM_TINT    0.35
#define END_SHOULDER   0.7
#define END_BLOCKLIGHT 0.55
#define END_LIGHT_COL  vec3(1.1, 0.9, 1.55)
#define END_LIGHT_AMT  0.85
#define END_DIM        0.55

// ===== awan pantulan =====
#define AWAN_SPEED 0.04
#define AWAN_SX 1.0
#define SISI_HALUS 0.15
#define AWAN_SPEED_DINDING 0.04
#define AWAN_SZ 1.0

// ===== bayangan awan (lantai dan sisi block) =====
#define BAYANG_AWAN 1
#define BAYANG_AWAN_TINGGI 40.0
#define BAYANG_AWAN_KUAT 0.38
#define BAYANG_AWAN_DITHER 0.02

// ===== glowing (alpha texture) =====
#if defined(ALPHA_TEST) || defined(TRANSPARENT)
#define GLOW_INTENSITY 0.95
#else
#define GLOW_INTENSITY 0.85
#endif
#define GLOW_SIANG 0.35
#define GLOW_HALO 0.30
#define GLOW_HALO_JARAK 6.0
#define GLOW_DENYUT 0.08

#define GLOW_MODE 1
#define GLOW_LANGIT_MIN 0.55
#define GLOW_LANGIT_MAX 0.90
#define GLOW_OBOR_MIN 0.30
#define GLOW_OBOR_MAX 0.75

// ===== ACESFilm tonemap =====
vec3 ACESFilm(vec3 x) {
    float a = 3.0;
    float b = 0.001;
    float c = 3.1;
    float d = 0.48;
    float e = 0.1;
    return (x * (a * x + b)) / (x * (c * x + d) + e);
}

vec3 Tm(vec3 x) {
#if defined(ALPHA_TEST)
    float a = 3.0;
#else
    float a = 4.1;
#endif
    float b = 0.01;
    float c = 2.6;
    float d = 0.56;
    float e = 0.4;
    return (x * (a * x + b)) / (x * (c * x + d) + e);
}

vec3 saturation(vec3 rgb, float adjustment) {
    const vec3 W = vec3(0.2125, 0.7154, 0.0721);
    float intensity = dot(rgb, W);
    return mix(vec3_splat(intensity), rgb, adjustment);
}

vec3 satuR(vec3 v, float val) {
    vec3 sat = vec3_splat(dot(v, vec3_splat(1.0 / 3.0)));
    return mix(sat, v, val);
}

bool endFog(vec4 f) {
    return (f.r > f.g && f.b > f.g && f.r < 0.05 && f.b < 0.05 && f.g < 0.05);
}

float hashAwan(vec2 q) {
    return fract(sin(dot(q, vec2(127.1, 311.7))) * 43758.5453);
}

// bayangan awan: 1 layer (tanpa loop)
float awanBayang(vec2 awBase) {
    float awDh = hashAwan(awBase * 999.0);
    return step(0.88, hashAwan(floor(awBase * 1.33 + vec2_splat(awDh * BAYANG_AWAN_DITHER + TIME * AWAN_SPEED))));
}

// ===== caustic =====
float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.13);
    p3 += dot(p3, p3.yzx + 3.333);
    return fract((p3.x + p3.y) * p3.z);
}

float noise(vec2 x) {
    x.y -= TIME * 0.02;
    vec2 i = floor(x);
    vec2 f = fract(x);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
}

// voronoi caustic (sama seperti macro F di shader asli: v.xyw *= mat3 * s)
float vorolit(vec2 uv) {
    uv -= vec2_splat(TIME * 0.02);
    uv *= 3.0;
    vec3 t = vec3(uv * (sin(TIME * 0.2) + 1.5) * 0.5, TIME * 0.3);
    vec3 c0 = vec3(0.0, -1.0, 2.0);
    vec3 c1 = vec3(3.0, -2.0, 1.0);
    vec3 c2 = vec3(1.0, 2.0, 2.0);
    t = vec3(dot(t, c0), dot(t, c1), dot(t, c2)) * 0.5;
    float fa = length(vec3_splat(0.5) - fract(t));
    t = vec3(dot(t, c0), dot(t, c1), dot(t, c2)) * 0.4;
    float fb = length(vec3_splat(0.5) - fract(t));
    t = vec3(dot(t, c0), dot(t, c1), dot(t, c2)) * 0.3;
    float fc = length(vec3_splat(0.5) - fract(t));
    return pow(min(min(fa, fb), fc), 3.0) * 4.0;
}

const vec3 RAY_D  = vec3( 0.324617, -0.927478,  0.185496);
const vec3 RAY_T1 = vec3(-0.943858, -0.330350,  0.0);
const vec3 RAY_T2 = vec3( 0.061279, -0.175082, -0.982645);

float causticRay(vec3 pp, vec3 nn) {
    vec2 q = vec2(dot(pp, RAY_T1), dot(pp, RAY_T2));
    float c = smoothstep(0.2, 1.0, vorolit(q * 0.35));
    c *= mix(0.6, 1.0, saturate(dot(nn, -RAY_D)));
    return c;
}

float godRayFog(vec3 pp, vec3 ww, vec2 fragXY) {
    float jit = fract(dot(fragXY, vec2(0.06711056, 0.00583715)) * 52.9829189);
    float acc = 0.0;
    for (int i = 0; i < 4; i++) {
        float t = (float(i) + jit) * 0.25;
        vec3 sp = pp - ww * (1.0 - t);
        vec2 q = vec2(dot(sp, RAY_T1), dot(sp, RAY_T2));
        acc += smoothstep(0.45, 0.8, noise(q * 0.9));
    }
    return acc * 0.25;
}

vec3 fresnel(vec3 f0, float NdotV) {
    return f0 + (1.0 - f0) * pow(1.0 - NdotV, 5.0);
}

// awan reflection (lantai & dinding)
float awanRef(vec3 awR, float spd) {
    vec2 awBase = vec2(AWAN_SX * awR.x, AWAN_SZ * awR.z) / max(awR.y, 0.02);
    float awDh = hashAwan(awBase * 999.0);
    float awShd = 0.0;
    float awMm = 1.0;
    float awSc = 1.4;
    for (int awI = 0; awI < 12; awI++) {
        awSc /= 1.009;
        awMm *= 0.96;
        float awAwan = step(0.88, hashAwan(floor(awBase * awSc + vec2_splat(awDh * 0.02 + TIME * spd))));
        awShd = mix(awShd, awMm, awAwan);
    }
    return awShd * smoothstep(0.0, 0.12, awR.y);
}

float genangan(vec2 q) {
    vec2 g = q / 4.0;
    vec2 id = floor(g);
    vec2 f = fract(g) - vec2_splat(0.5);
    vec2 c = (vec2(hash(id + vec2_splat(7.3)), hash(id + vec2_splat(13.1))) - vec2_splat(0.5)) * 0.2;
    vec2 hs = vec2_splat(0.15) + 0.17 * vec2(hash(id + vec2_splat(3.7)), hash(id + vec2_splat(9.9)));
    vec2 d = abs(f - c) - (hs - vec2_splat(0.1));
    float sd = length(max(d, vec2_splat(0.0))) + min(max(d.x, d.y), 0.0) - 0.1;
    return step(0.7, hash(id)) * (1.0 - smoothstep(-0.04, 0.04, sd));
}

// halo glow ore: 8 tap (tanpa array const supaya aman di HLSL)
float haloG(float a) {
    int c = int(a * 255.0 + 0.5);
    return (c == 252) ? 1.0 : ((c == 253) ? 0.4 : 0.0);
}

void haloCalc(vec2 uv, vec2 tx, out float hSum, out vec3 hCol) {
    vec4 t0 = texture2DLod(s_MatTexture, uv + vec2( 1.0,  0.0) * tx, 0.0);
    vec4 t1 = texture2DLod(s_MatTexture, uv + vec2(-1.0,  0.0) * tx, 0.0);
    vec4 t2 = texture2DLod(s_MatTexture, uv + vec2( 0.0,  1.0) * tx, 0.0);
    vec4 t3 = texture2DLod(s_MatTexture, uv + vec2( 0.0, -1.0) * tx, 0.0);
    vec4 t4 = texture2DLod(s_MatTexture, uv + vec2( 1.0,  1.0) * tx, 0.0);
    vec4 t5 = texture2DLod(s_MatTexture, uv + vec2(-1.0,  1.0) * tx, 0.0);
    vec4 t6 = texture2DLod(s_MatTexture, uv + vec2( 1.0, -1.0) * tx, 0.0);
    vec4 t7 = texture2DLod(s_MatTexture, uv + vec2(-1.0, -1.0) * tx, 0.0);
    float g0 = haloG(t0.w);
    float g1 = haloG(t1.w);
    float g2 = haloG(t2.w);
    float g3 = haloG(t3.w);
    float g4 = haloG(t4.w);
    float g5 = haloG(t5.w);
    float g6 = haloG(t6.w);
    float g7 = haloG(t7.w);
    hSum = g0 + g1 + g2 + g3 + 0.6 * (g4 + g5 + g6 + g7);
    hCol = t0.rgb * g0 + t1.rgb * g1 + t2.rgb * g2 + t3.rgb * g3
         + 0.6 * (t4.rgb * g4 + t5.rgb * g5 + t6.rgb * g6 + t7.rgb * g7);
}

void main() {
#if defined(DEPTH_ONLY) || defined(DEPTH_ONLY_OPAQUE) || defined(DEPTH_ONLY_ALPHA_TEST)

    #if defined(ALPHA_TEST) || defined(DEPTH_ONLY_ALPHA_TEST)
        vec4 depthDiffuse = texture2D(s_MatTexture, v_texcoord0);
        if (depthDiffuse.a < 0.5) discard;
    #endif

    gl_FragColor = vec4_splat(0.0);

#else

    vec4 fctl = FogAndDistanceControl;

#if defined(ALPHA_TEST)

#define kolor v_color0

    vec2 _1201 = v_ditheringAndMaskTinting;
    vec4 _822 = texture2D(s_MatTexture, v_texcoord0);

    vec4 oreTex = texture2DLod(s_MatTexture, v_texcoord0, 0.0);
    int oreCode = int(oreTex.w * 255.0 + 0.5);
    float oreGlow = (oreCode == 252) ? 1.0 : ((oreCode == 253) ? 0.4 : 0.0);
vec3 N = normalize(cross(dFdx(p.xyz), dFdy(p.xyz)));
    bool _ditherCut = false;
#if defined(DITHERING)
    if (_1201.x > 0.5)
    {
        _ditherCut = evaluateDitherDiscard(v_clipPosition, v_worldPosition.xyz, ViewPositionAndTime.xyz, u_view, DitherParams, DitherParams2[2]);
    }
#endif
    if (_ditherCut || (_822.w < 0.5))
    {
        discard;
    }
    vec4 _877 = _822;
    vec3 _879 = _877.xyz * v_color0.xyz;
    _822 = vec4(_879.x, _879.y, _879.z, _877.w);
    vec4 _1150 = v_fog;
    bool _akhirE = endFog(_1150);
    vec2 _lmUV = v_lightmapUV;
    if(_akhirE){ _lmUV.x *= END_BLOCKLIGHT; }
    vec3 _lm = texture2D(s_LightMapTexture, _lmUV).xyz;
    if(_akhirE){
    float _lmL = dot(_lm, vec3(0.2125, 0.7154, 0.0721));
    _lm = mix(vec3_splat(_lmL), _lm, END_LM_TINT);
    _lm = mix(_lm, _lmL * END_LIGHT_COL, saturate(v_lightmapUV.x) * END_LIGHT_AMT);
    }
    vec4 _finalColor = vec4(mix(vec4(_lm * _879.xyz, _822.w).xyz, FogColor.xyz, vec3_splat(_1150.w)), _822.w);
   
float dirAlpha = (max(min(N.r-N.b, 1.0), 0.0)+max(min(N.b-N.r, 1.0), 0.0));

//var
vec2 uv1 = v_lightmapUV;
vec4 fclr = v_fog;
float Apa = 0.6;

//detect
float goa = smoothstep(0.5,0.0, uv1.y);
float wk = texture2D(s_LightMapTexture, vec2(0.0, 1.0)).r;
wk = clamp((wk-0.5)*2.0, 0.0, 1.0);
float siangd = wk*uv1.y;
float malamd = 1.0-wk*uv1.y;
float sored = 0.5-abs(0.5-wk)*uv1.y;
  vec2 rf = vec2(0.5 + 20.0 / FogAndDistanceControl.z, 1.0);
  vec2 rt = clamp((FogAndDistanceControl.xy - rf) / (vec2(0.23, 0.7) - rf), 0.0, 1.0);
  float rk = rt.x * rt.y;
  float hujand = rk * rk * (3.0 - 2.0 * rk);
bool nether = (fclr.r>fclr.b && fclr.r<0.5 && fclr.b<0.2);
bool akhir = endFog(fclr);
bool udw = (fctl.x < 0.01) && !akhir;
float endK = (fctl.x < 0.01) ? END_DIM : 1.0;
bool banyu = (kolor.b > kolor.g || kolor.b > kolor.r);

//coloring, saturation and tonemap
if(kolor.g>kolor.r){
_finalColor.rgb *= max(1.1, 1.0-dirAlpha);
}
if(!akhir){
_finalColor.rgb *= vec3_splat(1.7);
_finalColor.rgb = saturation(_finalColor.rgb, 1.2);
_finalColor.xyz *= ACESFilm(_finalColor.xyz);
_finalColor.xyz = Tm(_finalColor.xyz);
}

//dirlight
if(!(kolor.g>kolor.r)){
float dir = max(N.x, -N.z);
_finalColor.xyz *= mix(vec3_splat(0.95), vec3_splat(0.558), dir * (1.0 - v_lightmapUV.x) * (1.0 - dirAlpha));
}

if(kolor.g>kolor.r){
_finalColor.rgb = mix(_finalColor.rgb, _finalColor.rgb*0.55, dirAlpha*(1.0 - v_lightmapUV.x));
}

vec3 V = normalize(-wp);
float NdotV = max(0.001, dot(N,V));
float isnether = step(fclr.b, fclr.r) * step(fclr.r, 0.4999) * step(fclr.b, 0.1999);
float isudw = step(fctl.x, 0.00999) * (akhir ? 0.0 : 1.0);
float isakhir = akhir ? 1.0 : 0.0;
float shadowRaw = smoothstep(1.0,0.95,uv1.y);
float shadow = shadowRaw;
shadow *= (1.0-uv1.x);
shadow *= (1.0-hujand)*(1.0-hujand)*(1.0-hujand)*(1.0-hujand)*(1.0-hujand);
shadow = mix(0.0,0.65,shadow);

//bayangan awan (lantai dan sisi block, bagian bawah tidak): warna shadow block tapi lebih terang
#if BAYANG_AWAN
if(N.y>-0.5){
// hilang: malam (wk), hujan, bawah air/nether/end, dan di bawah shadow block (goa/bangunan)
float bAktif = smoothstep(0.05, 0.3, wk) * (1.0 - hujand) * (1.0 - isudw) * (1.0 - isnether) * (1.0 - isakhir) * (1.0 - shadowRaw);
if(bAktif > 0.001){
// awan tepat di atas titik ini (matahari di atas kepala): base langit = xz / tinggi awan
float bDy = BAYANG_AWAN_TINGGI / sqrt(dot(wp.xz, wp.xz) + BAYANG_AWAN_TINGGI * BAYANG_AWAN_TINGGI);
float bMask = awanBayang(wp.xz / BAYANG_AWAN_TINGGI) * smoothstep(0.0, 0.12, bDy);
_finalColor.rgb = mix(_finalColor.rgb, vec3(0.0,0.0,0.12), BAYANG_AWAN_KUAT * bMask * bAktif);
}
}
#endif

//=====metalic=====
bool metalic = (oreCode == 250);
if (v_ditheringAndMaskTinting.y > 0.5) metalic = false;
float metalAktif = smoothstep(0.05, 0.3, wk) * (1.0 - hujand) * (1.0 - isudw) * (1.0 - isnether) * (1.0 - isakhir) * (1.0 - shadow / 0.65);
if(metalic && metalAktif > 0.001){
vec3 sebelumMetal = _finalColor.rgb;

//floor
if(N.y>0.5){
_finalColor.rgb = mix(_finalColor.rgb,mix(vec3(0.1,0.5,1.0)*1.2,vec3_splat(1.1),length(-ks)/90.0),fresnel(vec3_splat(0.3), NdotV));

if(!udw){
vec3 awR = reflect(-V, N);
vec2 awBase = vec2(AWAN_SX * awR.x, AWAN_SZ * awR.z) / max(awR.y, 0.02);
float awDh = hashAwan(awBase * 999.0);
float awShd = 0.0;
float awMm = 1.0;
float awSc = 1.4;
for (int awI = 0; awI < 12; awI++) {
awSc /= 1.009;
awMm *= 0.96;
float awAwan = step(0.88, hashAwan(floor(awBase * awSc + awDh * 0.02 + TIME * 0.04)));
awShd = mix(awShd, awMm, awAwan);
}
float awMask = awShd * smoothstep(0.0, 0.12, awR.y);

vec3 awHasil = mix(_finalColor.rgb, vec3_splat(1.0), awMask);
_finalColor.rgb = mix(_finalColor.rgb, awHasil, fresnel(vec3_splat(0.3), NdotV));
}
}

//sisi (dinding)
if(max(abs(N.x),abs(N.z))>0.5){
float awEl = wp.y / max(length(wp), 0.001);
float awAtas = smoothstep(-SISI_HALUS, SISI_HALUS, awEl);
vec3 sisiAtas = mix(vec3(0.1,0.5,1.0)*1.2, vec3_splat(1.1), clamp(length(wp.xz)/max(abs(wp.y),0.1)*0.1, 0.05, 0.7));
vec3 sisiBawah = vec3(212.0,232.0,255.0)/255.0;
_finalColor.rgb = mix(_finalColor.rgb, mix(sisiBawah, sisiAtas, awAtas), fresnel(vec3_splat(0.3), NdotV));

vec3 soreAtas = vec3(0.1,0.5,1.0)*1.2;
vec3 soreBawah = vec3(1.0,0.8,0.1);
_finalColor.rgb = mix(_finalColor.rgb, mix(soreBawah, soreAtas, awAtas), fresnel(vec3_splat(0.3), NdotV)*sored*(1.0-hujand));
}

//awan ref
if(wp.y>-0.05){
if(abs(N.x)>0.5){
if(!udw){
vec3 awR = reflect(-V, N);
vec2 awBase = vec2(AWAN_SX * awR.x, AWAN_SZ * awR.z) / max(awR.y, 0.02);
float awDh = hashAwan(awBase * 999.0);
float awShd = 0.0;
float awMm = 1.0;
float awSc = 1.4;
for (int awI = 0; awI < 12; awI++) {
awSc /= 1.009;
awMm *= 0.96;
float awAwan = step(0.88, hashAwan(floor(awBase * awSc + awDh * 0.02 + TIME * AWAN_SPEED_DINDING)));
awShd = mix(awShd, awMm, awAwan);
}
float awMask = awShd * smoothstep(0.0, 0.12, awR.y);

vec3 awHasil = mix(_finalColor.rgb, vec3_splat(1.0), awMask);
_finalColor.rgb = mix(_finalColor.rgb, awHasil, fresnel(vec3_splat(0.3), NdotV));
}
}

if(abs(N.z)>0.5){
if(!udw){
vec3 awR = reflect(-V, N);
vec2 awBase = vec2(AWAN_SX * awR.x, AWAN_SZ * awR.z) / max(awR.y, 0.02);
float awDh = hashAwan(awBase * 999.0);
float awShd = 0.0;
float awMm = 1.0;
float awSc = 1.4;
for (int awI = 0; awI < 12; awI++) {
awSc /= 1.009;
awMm *= 0.96;
float awAwan = step(0.88, hashAwan(floor(awBase * awSc + awDh * 0.02 + TIME * AWAN_SPEED_DINDING)));
awShd = mix(awShd, awMm, awAwan);
}
float awMask = awShd * smoothstep(0.0, 0.12, awR.y);

vec3 awHasil = mix(_finalColor.rgb, vec3_splat(1.0), awMask);
_finalColor.rgb = mix(_finalColor.rgb, awHasil, fresnel(vec3_splat(0.3), NdotV));
}
}

}
_finalColor.rgb = mix(sebelumMetal, _finalColor.rgb, metalAktif);
}


//end
if(akhir){
vec3 ec = _finalColor.rgb * END_BRIGHT * endK;
ec = saturation(ec, END_SAT);
vec3 eo = max(ec - END_SHOULDER, 0.0);
float ek = 1.0 - END_SHOULDER;
ec = min(ec, vec3_splat(END_SHOULDER)) + ek * (1.0 - exp(-eo / ek));
_finalColor.rgb = ec;

float drl = max(-N.x, -N.z);
_finalColor.xyz *= mix(vec3_splat(0.85), vec3_splat(0.65), drl * (1.0 - pow(v_lightmapUV.x*0.9,4.7)));
}

//underwater
if(udw){
_finalColor.rgb *= vec3(0.3,1.1,0.85);
_finalColor = mix(_finalColor,v_fog*sqrt(vec4(0.8,0.8,1.5,1.0)),Apa*saturate(length(-ks)/50.0));
_finalColor.rgb += _finalColor.rgb*(vec3(0.0,0.7,1.0)*1.15)*pow(v_lightmapUV.x*0.9,4.7);

//caustic
float skyMax = 0.9375;
float topBand = smoothstep(skyMax - 0.01, skyMax - 0.001, uv1.y);
float belowEye = 1.0 - smoothstep(0.7, 1.2, wp.y);
float inWater = mix(1.0, belowEye, topBand);
float lit = smoothstep(skyMax - 0.24, skyMax - 0.02, uv1.y);
float cmask = inWater * lit * (1.0 - saturate(length(-ks)/20.0));
if(cmask > 0.001){
    _finalColor.rgb += vec3(0.6,0.9,1.0) * causticRay(p, N) * 0.35 * cmask;
}

// god rays
if(wk > 0.05){
    float fog2 = smoothstep(1.0, 2.0, length(wp));
    if(fog2 > 0.01){
        float dens = godRayFog(p, wp, gl_FragCoord.xy);
        float bukanBawah = smoothstep(-0.7, -0.2, N.y);
        float a = dens * fog2 * wk * uv1.y * 0.5 * bukanBawah;
        _finalColor.rgb = mix(_finalColor.rgb, vec3(0.5,1.05,1.2), saturate(a));
    }
}
}

//glowing (alphatest)
{
    float oreFade = 1.0 - smoothstep(0.6, 1.0, length(-ks)/FogAndDistanceControl.z);
    float oreNaungan = 1.0;
#if GLOW_MODE == 1
oreNaungan = 1.0 - smoothstep(GLOW_LANGIT_MIN, GLOW_LANGIT_MAX, v_lightmapUV.y);
#elif GLOW_MODE == 2
oreNaungan = (1.0 - smoothstep(GLOW_LANGIT_MIN, GLOW_LANGIT_MAX, v_lightmapUV.y))
           * (1.0 - smoothstep(GLOW_OBOR_MIN, GLOW_OBOR_MAX, v_lightmapUV.x));
#endif
float oreTerang = mix(GLOW_SIANG, 1.0, malamd) * oreNaungan;
    float orePulse = 1.0 + GLOW_DENYUT * sin(TIME * 1.5);
    float oreK = oreTerang * oreFade * orePulse;

    if(oreGlow > 0.0){
        _finalColor.rgb += oreTex.rgb * oreGlow * GLOW_INTENSITY * oreK;
    }
    else if(GLOW_HALO > 0.0 && oreNaungan > 0.01 && length(-ks) < GLOW_HALO_JARAK){
        vec2 tx = 1.0 / vec2(textureSize(s_MatTexture, 0));
        float hSum; vec3 hCol; haloCalc(v_texcoord0, tx, hSum, hCol);
        if(hSum > 0.0){
            hCol /= hSum;
            _finalColor.rgb += hCol * saturate(hSum * 0.35) * GLOW_HALO * oreK;
        }
    }
}

//fog
_finalColor = mix(_finalColor, mix(_finalColor,v_fog*sqrt(vec4(1.1,1.1,1.1,1.0)),Apa*saturate(length(-ks)/100.0)), (1.0-hujand));
_finalColor = mix(_finalColor,mix(_finalColor,v_fog*sqrt(vec4(0.8,0.8,0.8,1.0)),Apa*saturate(length(-ks)/20.0)), hujand);

    gl_FragColor = _finalColor;

#undef kolor

#elif defined(TRANSPARENT)

    vec4 _1371 = v_color0;
    vec2 _1207 = v_ditheringAndMaskTinting;
    vec4 _820 = texture2D(s_MatTexture, v_texcoord0);

    // deteksi glow ore lewat alpha texture
    // alpha 252/255 = glow penuh, alpha 253/255 = glow redup
    vec4 oreTex = texture2DLod(s_MatTexture, v_texcoord0, 0.0);
    int oreCode = int(oreTex.w * 255.0 + 0.5);
    float oreGlow = (oreCode == 252) ? 1.0 : ((oreCode == 253) ? 0.4 : 0.0);
    // di block mask-tint alpha dipakai sebagai mask warna, jadi abaikan
    if (v_ditheringAndMaskTinting.y > 0.5) oreGlow = 0.0;
    if (_1207.y > 0.5)
    {
        vec3 _872 = mix(_820.xyz, _820.xyz * v_color0.xyz, vec3_splat(_820.w)).xyz * _1371.w;
        _820 = vec4(_872.x, _872.y, _872.z, _820.w);
        _820.w = 1.0;
    }
    else
    {
        vec3 _880 = _820.xyz * v_color0.xyz;
        _820 = vec4(_880.x, _880.y, _880.z, _820.w);
        _820.w *= _1371.w;
    }
#if defined(DITHERING)
    if (_1207.x > 0.5)
    {
        if (evaluateDitherDiscard(v_clipPosition, v_worldPosition.xyz, ViewPositionAndTime.xyz, u_view, DitherParams, DitherParams2[2]))
        {
            _820.w = 0.0;
        }
    }
#endif
    vec4 _1161 = v_fog;

//var
vec2 uv1 = v_lightmapUV;
vec4 fclr = v_fog;
vec4 kolor = v_color0;
vec3 N = normalize(cross(dFdx(p.xyz), dFdy(p.xyz)));
vec3 V = normalize(-wp);
vec3 f0 = vec3_splat(0.05);
float NdotV = max(0.001, dot(N,V));
vec3 pot = p*0.2;

//detect
float goa = smoothstep(0.5,0.0, uv1.y);
float wk = texture2D(s_LightMapTexture, vec2(0.0, 1.0)).r;
wk = clamp((wk-0.5)*2.0, 0.0, 1.0);
float siangd = wk*uv1.y;
float malamd = 1.0-wk*uv1.y;
float sored = 0.5-abs(0.5-wk)*uv1.y;
  vec2 rf = vec2(0.5 + 20.0 / FogAndDistanceControl.z, 1.0);
  vec2 rt = clamp((FogAndDistanceControl.xy - rf) / (vec2(0.23, 0.7) - rf), 0.0, 1.0);
  float rk = rt.x * rt.y;
  float hujand = rk * rk * (3.0 - 2.0 * rk);
bool nether = (fclr.r>fclr.b && fclr.r<0.5 && fclr.b<0.2);
bool akhir = endFog(fclr);
bool udw = (fctl.x == 0.0) && !akhir;
float endK = (fctl.x == 0.0) ? END_DIM : 1.0;
bool banyu = (kolor.b > kolor.g || kolor.b > kolor.r);


    vec2 _lmUV = v_lightmapUV;
    if(akhir){ _lmUV.x *= END_BLOCKLIGHT; }
    vec3 _lm = texture2D(s_LightMapTexture, _lmUV).xyz;
    if(akhir){
    float _lmL = dot(_lm, vec3(0.2125, 0.7154, 0.0721));
    _lm = mix(vec3_splat(_lmL), _lm, END_LM_TINT);
    _lm = mix(_lm, _lmL * END_LIGHT_COL, saturate(v_lightmapUV.x) * END_LIGHT_AMT);
    }
    vec4 _finalColor = vec4(mix(vec4(_lm * _820.xyz, _820.w).xyz, FogColor.xyz, vec3_splat(_1161.w)), _820.w);
    
float wave = sin(noise(vec2(TIME*0.7+pot.x+pot.x+pot.z*3.0, TIME*1.2+pot.z+pot.x+pot.z*1.0+pot.x*2.0)));
_finalColor.rgb += wave*0.1*(vec3(0.3,0.5,1.2)*0.7)*(1.0-hujand)*siangd;
    
if(!(banyu)){
if(!akhir){
_finalColor.rgb *= vec3_splat(1.7);
_finalColor.rgb = saturation(_finalColor.rgb, 1.2);
if(!(udw)){
_finalColor.xyz *= ACESFilm(_finalColor.xyz);}
_finalColor.xyz = Tm(_finalColor.xyz);
}

//variabel pendukung metalic (di shader ini belum ada)
float isnether = step(fclr.b, fclr.r) * step(fclr.r, 0.4999) * step(fclr.b, 0.1999);
float isudw = step(fctl.x, 0.00999) * (akhir ? 0.0 : 1.0);
float isakhir = akhir ? 1.0 : 0.0;
float shadowRaw = smoothstep(1.0,0.95,uv1.y);
float shadow = shadowRaw;
shadow *= (1.0-uv1.x);
shadow *= (1.0-hujand)*(1.0-hujand)*(1.0-hujand)*(1.0-hujand)*(1.0-hujand);
shadow = mix(0.0,0.65,shadow);

//bayangan awan (sisi atas dan samping, bagian bawah tidak): warna shadow block tapi lebih terang
#if BAYANG_AWAN
if(N.y>-0.5){
// hilang: malam (wk), hujan, bawah air/nether/end, dan di bawah shadow block (goa/bangunan)
float bAktif = smoothstep(0.05, 0.3, wk) * (1.0 - hujand) * (1.0 - isudw) * (1.0 - isnether) * (1.0 - isakhir) * (1.0 - shadowRaw);
if(bAktif > 0.001){
// awan tepat di atas titik ini: base langit = xz / tinggi awan
float bDy = BAYANG_AWAN_TINGGI / sqrt(dot(wp.xz, wp.xz) + BAYANG_AWAN_TINGGI * BAYANG_AWAN_TINGGI);
float bMask = awanBayang(wp.xz / BAYANG_AWAN_TINGGI) * smoothstep(0.0, 0.12, bDy);
_finalColor.rgb = mix(_finalColor.rgb, vec3(0.0,0.0,0.12), BAYANG_AWAN_KUAT * bMask * bAktif);
}
}
#endif

//=====metalic=====
bool metalic = (oreCode == 250);
if (v_ditheringAndMaskTinting.y > 0.5) metalic = false;
float metalAktif = smoothstep(0.05, 0.3, wk) * (1.0 - hujand) * (1.0 - isudw) * (1.0 - isnether) * (1.0 - isakhir) * (1.0 - shadow / 0.65);
//if(metalic && metalAktif > 0.001){
vec3 sebelumMetal = _finalColor.rgb;

//floor
if(N.y>0.5){
_finalColor.rgb = mix(_finalColor.rgb, mix(_finalColor.rgb,mix(vec3(0.1,0.5,1.0)*1.2,vec3_splat(1.1),length(-wp)/70.0),fresnel(vec3_splat(0.3), NdotV)), siangd*(1.0-hujand));
_finalColor.rgb = mix(_finalColor.rgb, mix(_finalColor.rgb,mix(vec3(0.1,0.5,1.0)*1.2,vec3(1.0,0.8,0.1),length(-wp)/70.0),fresnel(vec3_splat(0.3), NdotV)), sored*(1.0-hujand));

if(!udw){
vec3 awR = reflect(-V, N);
vec2 awBase = vec2(AWAN_SX * awR.x, AWAN_SZ * awR.z) / max(awR.y, 0.02);
float awDh = hashAwan(awBase * 999.0);
float awShd = 0.0;
float awMm = 1.0;
float awSc = 1.4;
for (int awI = 0; awI < 12; awI++) {
awSc /= 1.009;
awMm *= 0.96;
float awAwan = step(0.88, hashAwan(floor(awBase * awSc + awDh * 0.02 + TIME * 0.04)));
awShd = mix(awShd, awMm, awAwan);
}
float awMask = awShd * smoothstep(0.0, 0.12, awR.y);

vec3 awHasil = mix(_finalColor.rgb, vec3_splat(1.0), awMask);
_finalColor.rgb = mix(_finalColor.rgb, awHasil, fresnel(vec3_splat(0.3), NdotV));
}
}

//sisi (dinding): warna atas dan bawah dicampur halus di sekitar tinggi mata
if(max(abs(N.x),abs(N.z))>0.5){
float awEl = wp.y / max(length(wp), 0.001);
float awAtas = smoothstep(-SISI_HALUS, SISI_HALUS, awEl);
vec3 sisiAtas = mix(vec3(0.1,0.5,1.0)*1.2, vec3_splat(1.1), clamp(length(wp.xz)/max(abs(wp.y),0.1)*0.1, 0.05, 0.7));
vec3 sisiBawah = vec3(212.0,232.0,255.0)/255.0;
_finalColor.rgb = mix(_finalColor.rgb, mix(sisiBawah, sisiAtas, awAtas), fresnel(vec3_splat(0.3), NdotV));

vec3 soreAtas = vec3(0.1,0.5,1.0)*1.2;
vec3 soreBawah = vec3(1.0,0.8,0.1);
_finalColor.rgb = mix(_finalColor.rgb, mix(soreBawah, soreAtas, awAtas), fresnel(vec3_splat(0.3), NdotV)*sored*(1.0-hujand));
}

//awan ref
if(wp.y>-0.05){
if(abs(N.x)>0.5){
if(!udw){
vec3 awR = reflect(-V, N);
vec2 awBase = vec2(AWAN_SX * awR.x, AWAN_SZ * awR.z) / max(awR.y, 0.02);
float awDh = hashAwan(awBase * 999.0);
float awShd = 0.0;
float awMm = 1.0;
float awSc = 1.4;
for (int awI = 0; awI < 12; awI++) {
awSc /= 1.009;
awMm *= 0.96;
float awAwan = step(0.88, hashAwan(floor(awBase * awSc + awDh * 0.02 + TIME * AWAN_SPEED_DINDING)));
awShd = mix(awShd, awMm, awAwan);
}
float awMask = awShd * smoothstep(0.0, 0.12, awR.y);

vec3 awHasil = mix(_finalColor.rgb, vec3_splat(1.0), awMask);
_finalColor.rgb = mix(_finalColor.rgb, awHasil, fresnel(vec3_splat(0.3), NdotV));
}
}

if(abs(N.z)>0.5){
if(!udw){
vec3 awR = reflect(-V, N);
vec2 awBase = vec2(AWAN_SX * awR.x, AWAN_SZ * awR.z) / max(awR.y, 0.02);
float awDh = hashAwan(awBase * 999.0);
float awShd = 0.0;
float awMm = 1.0;
float awSc = 1.4;
for (int awI = 0; awI < 12; awI++) {
awSc /= 1.009;
awMm *= 0.96;
float awAwan = step(0.88, hashAwan(floor(awBase * awSc + awDh * 0.02 + TIME * AWAN_SPEED_DINDING)));
awShd = mix(awShd, awMm, awAwan);
}
float awMask = awShd * smoothstep(0.0, 0.12, awR.y);

vec3 awHasil = mix(_finalColor.rgb, vec3_splat(1.0), awMask);
_finalColor.rgb = mix(_finalColor.rgb, awHasil, fresnel(vec3_splat(0.3), NdotV));
}
}

}
_finalColor.rgb = mix(sebelumMetal, _finalColor.rgb, metalAktif);
//}

//end
if(akhir){
vec3 ec = _finalColor.rgb * END_BRIGHT * endK;
ec = saturation(ec, END_SAT);
vec3 eo = max(ec - END_SHOULDER, 0.0);
float ek = 1.0 - END_SHOULDER;
ec = min(ec, vec3_splat(END_SHOULDER)) + ek * (1.0 - exp(-eo / ek));
_finalColor.rgb = ec;

float drl = max(-N.x, -N.z);
_finalColor.xyz *= mix(vec3_splat(0.85), vec3_splat(0.65), drl * (1.0 - pow(v_lightmapUV.x*0.9,4.7)));
}

}

//warna air
if(banyu){
_finalColor.rgb *= vec3_splat(0.0);
_finalColor = vec4(0.3,0.5,1.0,1.0)*0.7*siangd*(1.0-hujand);
_finalColor += vec4(0.3,0.5,0.7,1.2)*0.5*malamd*(1.0-hujand);
_finalColor.rgb += vec3_splat(0.5)*0.3*hujand;
_finalColor.rgb += vec3(0.1,0.5,1.0)*sored*(1.0-hujand);
_finalColor.rgb = satuR(_finalColor.rgb, 1.2)*siangd*(1.0-goa);
_finalColor.rgb += vec3(0.3,0.5,0.7)*goa;

//transparansi air
_finalColor.a = length(-wp)/15.0;
_finalColor.a *= 1.5;

//wave air
_finalColor.rgb += wave*0.1*vec3(0.3,0.5,1.0)*0.7*malamd;
_finalColor.rgb += wave*0.1*vec3_splat(1.0)*0.3*hujand;

//shadow block di air
float shadow = smoothstep(1.0,0.95,uv1.y+wave*0.05);
float nolight = (1.0-uv1.x);
shadow *= nolight;
shadow *= (1.0-hujand)*(1.0-hujand)*(1.0-hujand)*(1.0-hujand)*(1.0-hujand);
shadow = mix(0.0,0.65,shadow);

//awan ref P
if(N.y>0.5 && !udw){
vec3 awR = reflect(-V, N);
awR.xz += wave*0.05;
vec2 awBase = vec2(AWAN_SX * awR.x, AWAN_SZ * awR.z) / max(awR.y, 0.02);
float awDh = hashAwan(awBase * 999.0);
float awShd = 0.0;
float awMm = 1.0;
float awSc = 1.4;
for (int awI = 0; awI < 12; awI++) {
awSc /= 1.009;
awMm *= 0.96;
float awAwan = step(0.88, hashAwan(floor(awBase * awSc + awDh * 0.02 + TIME * AWAN_SPEED)));
awShd = mix(awShd, awMm, awAwan);
}
// refleksi awan hilang saat hujan (hujand) dan malam (wk = tingkat terang siang)
float awSiang = smoothstep(0.2, 0.8, wk);
float awMask = awShd * smoothstep(0.0, 0.12, awR.y)*0.3*(1.0-shadow/0.65)*(1.0-hujand)*awSiang;

vec3 awHasil = mix(_finalColor.rgb, vec3_splat(1.0), awMask);
_finalColor.rgb = mix(_finalColor.rgb, awHasil, fresnel(vec3_splat(0.4), NdotV));
}

_finalColor.rgb = mix(_finalColor.rgb,vec3(0.0,0.0,0.08),shadow*(0.6+wave*0.4));

_finalColor.rgb += fresnel(f0, NdotV)*0.7*(1.0-malamd);
}

//udw
float uw = float(FogAndDistanceControl.x == 0.0 && FogAndDistanceControl.y < 0.8 && (FogColor.z > FogColor.x || FogColor.y > FogColor.x));
if ((kolor.b > kolor.g || kolor.b > kolor.r) && uw > 0.5 && N.y < -0.5) {
    _finalColor.rgb = mix(_finalColor.rgb, vec3(0.1, 0.45, 0.9), 0.85);
_finalColor.rgb += fresnel(f0, NdotV)*0.5*(1.0-malamd);

//transparansi air
_finalColor.a = length(-wp)/15.0+wave*0.05;
_finalColor.a *= 1.5;
}


//glowing ore
float oreDist = length(wp);
float oreFade = 1.0 - smoothstep(0.6, 1.0, oreDist/FogAndDistanceControl.z);   // memudar di kejauhan
float oreNaungan = 1.0;
#if GLOW_MODE == 1
oreNaungan = 1.0 - smoothstep(GLOW_LANGIT_MIN, GLOW_LANGIT_MAX, v_lightmapUV.y);
#elif GLOW_MODE == 2
oreNaungan = (1.0 - smoothstep(GLOW_LANGIT_MIN, GLOW_LANGIT_MAX, v_lightmapUV.y))
           * (1.0 - smoothstep(GLOW_OBOR_MIN, GLOW_OBOR_MAX, v_lightmapUV.x));
#endif
float oreTerang = mix(GLOW_SIANG, 1.0, malamd) * oreNaungan;                                 // siang redup, gelap penuh
float orePulse = 1.0 + GLOW_DENYUT * sin(TIME * 1.5);                           // denyut halus
float oreK = oreTerang * oreFade * orePulse;

if(oreGlow > 0.0){
    // inti glow: warna piksel itu sendiri
    _finalColor.rgb += oreTex.rgb * oreGlow * GLOW_INTENSITY * oreK;
}
else if(GLOW_HALO > 0.0 && oreNaungan > 0.01 && v_ditheringAndMaskTinting.y < 0.5 && oreDist < GLOW_HALO_JARAK){
    // halo: piksel biasa yang bertetangga dengan piksel glow ikut sedikit menyala
    vec2 tx = 1.0 / vec2(textureSize(s_MatTexture, 0));
    float hSum; vec3 hCol; haloCalc(v_texcoord0, tx, hSum, hCol);
    if(hSum > 0.0){
        hCol /= hSum;
        _finalColor.rgb += hCol * saturate(hSum * 0.35) * GLOW_HALO * oreK;
    }
}

float Apa = 0.6;
_finalColor = mix(_finalColor, mix(_finalColor,v_fog*sqrt(vec4(1.1,1.1,1.1,1.0)),Apa*saturate(length(-wp)/100.0)), (1.0-hujand));

    gl_FragColor = _finalColor;

#else

vec4 _811 = v_color0;
vec2 _671 = v_ditheringAndMaskTinting;
vec4 _552 = texture2D(s_MatTexture, v_texcoord0);

// deteksi glow ore lewat alpha texture
vec4 oreTex = texture2DLod(s_MatTexture, v_texcoord0, 0.0);
int oreCode = int(oreTex.w * 255.0 + 0.5);
float oreGlow = (oreCode == 252) ? 1.0 : ((oreCode == 253) ? 0.4 : 0.0);
if (v_ditheringAndMaskTinting.y > 0.5) oreGlow = 0.0;

//var 1
vec4 color = v_color0;
vec3 N = normalize(cross(dFdx(p.xyz), dFdy(p.xyz)));
vec3 V = normalize(-wp);
float NdotV = max(0.001, dot(N,V));
float lenKs = length(-ks);
float lenWp = length(wp);

if (_671.y > 0.5)
{
vec3 _588 = mix(_552.xyz, _552.xyz * v_color0.xyz, vec3_splat(_552.w)).xyz * _811.w;
_552 = vec4(_588.x, _588.y, _588.z, _552.w);
_552.w = 1.0;
}
else
{
vec3 _aoMul = v_color0.xyz;
if (abs(v_color0.g - v_color0.r) < 0.002) {
float ao = mix(0.55, 0.8, smoothstep(0.3, 0.7, v_color0.g));
float terbuka = smoothstep(0.55, 0.9, v_lightmapUV.y);
terbuka = max(terbuka, step(fctl.x, 0.00999) * (endFog(v_fog) ? 0.0 : 1.0));
ao = mix(0.8, ao, terbuka); 
_aoMul = vec3_splat(ao);
}
vec3 _596 = _552.xyz * _aoMul;
_552 = vec4(_596.x, _596.y, _596.z, _552.w);
_552.w = _811.w;
}
vec4 _666 = v_fog;
bool _akhirE = endFog(_666);
vec2 _lmUV = v_lightmapUV;
if(_akhirE){ _lmUV.x *= END_BLOCKLIGHT; }
vec3 _lm = texture2D(s_LightMapTexture, _lmUV).xyz;
if(_akhirE){
// lightmap End: kurangi tint warna (penyebab kuning/hijau neon), sisakan sedikit warna
float _lmL = dot(_lm, vec3(0.2125, 0.7154, 0.0721));
_lm = mix(vec3_splat(_lmL), _lm, END_LM_TINT);
_lm = mix(_lm, _lmL * END_LIGHT_COL, saturate(v_lightmapUV.x) * END_LIGHT_AMT);
}
vec4 _finalColor = vec4(mix(vec4(_lm * _552.xyz, _552.w).xyz, FogColor.xyz, vec3_splat(_666.w)), _552.w);

//var
vec2 uv1 = v_lightmapUV;
vec4 fclr = v_fog;
float Apa = 0.6;

//detect
float goa = smoothstep(0.5,0.0, uv1.y);
float wk = texture2D(s_LightMapTexture, vec2(0.0, 1.0)).r;
wk = clamp((wk-0.5)*2.0, 0.0, 1.0);
float siangd = wk*uv1.y;
float malamd = 1.0-wk*uv1.y;
float sored = 0.5-abs(0.5-wk)*uv1.y;
vec2 rf = vec2(0.5 + 20.0 / FogAndDistanceControl.z, 1.0);
vec2 rt = clamp((FogAndDistanceControl.xy - rf) / (vec2(0.23, 0.7) - rf), 0.0, 1.0);
float rk = rt.x * rt.y;
float hujand = rk * rk * (3.0 - 2.0 * rk);
float dudan = smoothstep(0.55,0.1,fctl.r)*uv1.y;
bool nether = (fclr.r>fclr.b && fclr.r<0.5 && fclr.b<0.2);
bool udw = (fctl.x < 0.01) && !endFog(fclr);
bool akhir = endFog(fclr);
float endK = (fctl.x < 0.01) ? END_DIM : 1.0;

float isnether = step(fclr.b, fclr.r) * step(fclr.r, 0.4999) * step(fclr.b, 0.1999);
float isudw = step(fctl.x, 0.00999) * (akhir ? 0.0 : 1.0);
float isakhir = akhir ? 1.0 : 0.0;

//dirlight
float dir = max(N.x, -N.z);
_finalColor.xyz *= mix(vec3_splat(0.75), vec3_splat(0.65), dir * (1.0 - pow(v_lightmapUV.x*0.9,4.7)) * (1.0 - goa) * (1.0-hujand)*(1.0-malamd));

//coloring, saturation and tonemapping
if(!akhir){
_finalColor.rgb *= vec3_splat(1.7);
_finalColor.rgb = saturation(_finalColor.rgb, 1.2);
if(!(udw)){
_finalColor.xyz *= ACESFilm(_finalColor.xyz);}
_finalColor.xyz = Tm(_finalColor.xyz);
if(color.a==0.0){
_finalColor.rgb *= 1.1;
}
}
/*
float malamLuar = malamd * smoothstep(0.4, 0.9, uv1.y) * (1.0 - hujand);
if(!nether && !akhir && !udw){
//_finalColor.rgb *= mix(vec3_splat(1.3), vec3_splat(2.8), malamLuar);
}
*/
//_finalColor.rgb *= mix(_finalColor.rgb, vec3_splat(1.8), malamd*(1.0-siangd)*(1.0-dudan));

//shadow
float shadowRaw = smoothstep(1.0,0.95,uv1.y);
float shadow = shadowRaw;
float nolight = (1.0-uv1.x);
shadow *= nolight;
shadow *= (1.0-hujand)*(1.0-hujand)*(1.0-hujand)*(1.0-hujand)*(1.0-hujand)*(1.0-malamd)*(1.0-malamd)*(1.0-malamd)*(1.0-malamd)*(1.0-malamd);
shadow = mix(0.0,0.65,shadow);
_finalColor.rgb = mix(_finalColor.rgb,vec3(0.0,0.0,0.12),shadow);

//bayangan awan (lantai dan sisi block, bagian bawah tidak): warna shadow block tapi lebih terang
#if BAYANG_AWAN
if(N.y>-0.5){
// hilang: malam (wk), hujan, bawah air/nether/end, dan di bawah shadow block (goa/bangunan)
float bAktif = smoothstep(0.05, 0.3, wk) * (1.0 - hujand) * (1.0 - isudw) * (1.0 - isnether) * (1.0 - isakhir) * (1.0 - shadowRaw);
if(bAktif > 0.001){
// awan tepat di atas titik ini (matahari di atas kepala): base langit = xz / tinggi awan
float bDy = BAYANG_AWAN_TINGGI / sqrt(dot(wp.xz, wp.xz) + BAYANG_AWAN_TINGGI * BAYANG_AWAN_TINGGI);
float bMask = awanBayang(wp.xz / BAYANG_AWAN_TINGGI) * smoothstep(0.0, 0.12, bDy);
_finalColor.rgb = mix(_finalColor.rgb, vec3(0.0,0.0,0.12), BAYANG_AWAN_KUAT * bMask * bAktif);
}
}
#endif

//=====metalic=====
bool metalic = (oreCode == 250);
if (v_ditheringAndMaskTinting.y > 0.5) metalic = false;
float metalAktif = smoothstep(0.05, 0.3, wk) * (1.0 - hujand) * (1.0 - isudw) * (1.0 - isnether) * (1.0 - isakhir) * (1.0 - shadow / 0.65);
if(metalic && metalAktif > 0.001){
vec3 sebelumMetal = _finalColor.rgb;
vec3 fr = fresnel(vec3_splat(0.3), NdotV);

//floor
if(N.y>0.5){
_finalColor.rgb = mix(_finalColor.rgb, mix(_finalColor.rgb,mix(vec3(0.1,0.5,1.0)*1.2,vec3_splat(1.1),lenKs/70.0),fr), siangd*(1.0-hujand));
_finalColor.rgb = mix(_finalColor.rgb, mix(_finalColor.rgb,mix(vec3(0.1,0.5,1.0)*1.2,vec3(1.0,0.8,0.1),lenKs/70.0),fr), sored*(1.0-hujand));

if(!udw){
vec3 awR = reflect(-V, N);
if(awR.y > 0.0){
float awMask = awanRef(awR, 0.04);
vec3 awHasil = mix(_finalColor.rgb, vec3_splat(1.0), awMask);
_finalColor.rgb = mix(_finalColor.rgb, awHasil, fr);
}
}
}

//sisi (dinding): warna atas dan bawah dicampur halus di sekitar tinggi mata
if(max(abs(N.x),abs(N.z))>0.5){
float awEl = wp.y / max(lenWp, 0.001);
float awAtas = smoothstep(-SISI_HALUS, SISI_HALUS, awEl);
vec3 sisiAtas = mix(vec3(0.1,0.5,1.0)*1.2, vec3_splat(1.1), clamp(length(wp.xz)/max(abs(wp.y),0.1)*0.1, 0.05, 0.7));
vec3 sisiBawah = vec3(212.0,232.0,255.0)/255.0;
_finalColor.rgb = mix(_finalColor.rgb, mix(sisiBawah, sisiAtas, awAtas), fr);

vec3 soreAtas = vec3(0.1,0.5,1.0)*1.2;
vec3 soreBawah = vec3(1.0,0.8,0.1);
_finalColor.rgb = mix(_finalColor.rgb, mix(soreBawah, soreAtas, awAtas), fr*sored*(1.0-hujand));
}

//awan ref (dinding)
if(wp.y>-0.05 && !udw){
bool awX = abs(N.x)>0.5;
bool awZ = abs(N.z)>0.5;
if(awX || awZ){
vec3 awR = reflect(-V, N);
if(awR.y > 0.0){
float awMask = awanRef(awR, AWAN_SPEED_DINDING);
if(awX){
vec3 awHasil = mix(_finalColor.rgb, vec3_splat(1.0), awMask);
_finalColor.rgb = mix(_finalColor.rgb, awHasil, fr);
}
if(awZ){
vec3 awHasil = mix(_finalColor.rgb, vec3_splat(1.0), awMask);
_finalColor.rgb = mix(_finalColor.rgb, awHasil, fr);
}
}
}
}
_finalColor.rgb = mix(sebelumMetal, _finalColor.rgb, metalAktif);
}

//hujan
if(!(udw && nether && akhir)){
_finalColor.rgb += vec3(-0.2,-0.1,-0.05)*dudan*(1.0-malamd);
//genangan
if(hujand > 0.0){
float langitTerbuka = smoothstep(0.88, 0.94, uv1.y);
float gn = genangan(p.xz) * langitTerbuka * hujand;
vec3 gnBasah = mix(_finalColor.rgb, vec3_splat(dot(_finalColor.rgb, vec3_splat(0.333))), 0.4) * 0.7;
vec3 gnKilap = mix(gnBasah, FogColor.rgb * 1.2 + 0.1, fresnel(vec3_splat(0.15), NdotV));
_finalColor.rgb = mix(_finalColor.rgb, gnKilap, gn);
}
}

//light
if(!(akhir)){
float jitB = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
float Lb = uv1.x + (jitB - 0.5) * 0.0625;
float core = smoothstep(0.72, 0.94, Lb);
core *= core;
float night = 1.0 - siangd * 0.8;
float flick = 0.9 + 0.1 * sin(TIME * 3.0 + p.x * 0.7 + p.z * 0.7);
float glow = core * night * flick * (1.0 - saturate(lenWp / 80.0));
_finalColor.rgb += vec3(1.0, 0.55, 0.2) * glow * 0.6 * (1.0 - saturate(_finalColor.rgb));
}

//underwater
if(udw && !(nether && akhir)){
_finalColor.rgb *= vec3(0.3,1.1,0.85);
_finalColor = mix(_finalColor,v_fog*sqrt(vec4(0.8,1.5,1.4,1.0)),Apa*saturate(lenKs/50.0));
_finalColor.rgb += _finalColor.rgb*(vec3(0.0,0.7,1.0)*1.15)*pow(v_lightmapUV.x*0.5,4.7);
_finalColor.xyz *= ACESFilm(_finalColor.xyz);

//caustic
float skyMax = 0.9375;
float topBand = smoothstep(skyMax - 0.01, skyMax - 0.001, uv1.y);
float belowEye = 1.0 - smoothstep(0.7, 1.2, wp.y);
float inWater = mix(1.0, belowEye, topBand);
float lit = smoothstep(skyMax - 0.24, skyMax - 0.02, uv1.y);
float cmask = inWater * lit * (1.0 - saturate(lenKs/20.0));
if(cmask > 0.001){
_finalColor.rgb += vec3(0.6,0.9,1.0) * causticRay(p, N) * 0.35 * cmask;
}

// god rays
if(wk > 0.05){
float fog2 = smoothstep(1.0, 2.0, lenWp);
if(fog2 > 0.01){
float dens = godRayFog(p, wp, gl_FragCoord.xy);
float bukanBawah = smoothstep(-0.7, -0.2, N.y);
float a = dens * fog2 * wk * uv1.y * 0.5 * bukanBawah;
_finalColor.rgb = mix(_finalColor.rgb, vec3(0.5,1.05,1.2), saturate(a));
}}}

//nether
if(nether){
 _finalColor.rgb *= vec3(1.5,0.9,0.0)*(1.0-sored);
_finalColor.rgb += vec3_splat(0.2);
_finalColor.rgb = Tm(_finalColor.rgb)*0.9;
//_finalColor.rgb += _finalColor.rgb*(vec3(1.0,0.5,0.0)*1.15)*pow(uv1.x*1.0,5.8);
vec3 fogn = mix(_finalColor.rgb, fclr.rgb*vec3(1.5,1.0,0.0), clamp(lenKs/FogAndDistanceControl.z*1.5,0.1,0.2));
_finalColor.rgb = fogn;
}

//glowing ore
float oreFade = 1.0 - smoothstep(0.6, 1.0, lenKs/FogAndDistanceControl.z);
float oreNaungan = 1.0;
#if GLOW_MODE == 1
oreNaungan = 1.0 - smoothstep(GLOW_LANGIT_MIN, GLOW_LANGIT_MAX, v_lightmapUV.y);
#elif GLOW_MODE == 2
oreNaungan = (1.0 - smoothstep(GLOW_LANGIT_MIN, GLOW_LANGIT_MAX, v_lightmapUV.y))
* (1.0 - smoothstep(GLOW_OBOR_MIN, GLOW_OBOR_MAX, v_lightmapUV.x));
#endif
float oreTerang = mix(GLOW_SIANG, 1.0, malamd) * oreNaungan;
float orePulse = 1.0 + GLOW_DENYUT * sin(TIME * 1.5);
float oreK = oreTerang * oreFade * orePulse;

if(oreGlow > 0.0){
_finalColor.rgb += oreTex.rgb * oreGlow * GLOW_INTENSITY * oreK;
}
else if(GLOW_HALO > 0.0 && oreNaungan > 0.01 && v_ditheringAndMaskTinting.y < 0.5 && lenKs < GLOW_HALO_JARAK){
vec2 tx = 1.0 / vec2(textureSize(s_MatTexture, 0));
float hSum; vec3 hCol; haloCalc(v_texcoord0, tx, hSum, hCol);
if(hSum > 0.0){
hCol /= hSum;
_finalColor.rgb += hCol * saturate(hSum * 0.35) * GLOW_HALO * oreK;
}}

//end
if(akhir){
vec3 ec = _finalColor.rgb * END_BRIGHT * endK;
ec = saturation(ec, END_SAT);
vec3 eo = max(ec - END_SHOULDER, 0.0);
float ek = 1.0 - END_SHOULDER;
ec = min(ec, vec3_splat(END_SHOULDER)) + ek * (1.0 - exp(-eo / ek));
_finalColor.rgb = ec;

float drl = max(max(-N.x, -N.z), -N.y);
_finalColor.xyz *= mix(vec3_splat(0.95), vec3_splat(0.5), drl * (1.0 - v_lightmapUV.x));

}

//fog
if(!(akhir)){
vec4 fgst = mix(_finalColor, mix(_finalColor,v_fog*sqrt(vec4(1.1,1.1,1.1,1.0)),Apa*saturate(lenKs/100.0)), (1.0-hujand));
_finalColor = fgst;
_finalColor = mix(_finalColor, mix(_finalColor,v_fog*sqrt(vec4(0.8,0.8,0.8,1.0)),Apa*saturate(lenKs/20.0)), dudan);
}

gl_FragColor = _finalColor;

#endif

#endif
}
