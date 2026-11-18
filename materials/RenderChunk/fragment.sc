
$input v_clipPosition, v_worldPosition, v_color0, v_ditheringAndMaskTinting, v_fog, v_lightmapUV, v_texcoord0, v_worldPos
$input p, wp, fctl, ks

#include <bgfx_shader.sh>

uniform vec4 FogColor;
uniform vec4 FogAndDistanceControl;
uniform vec4 ViewPositionAndTime;

SAMPLER2D_AUTOREG(s_MatTexture);
SAMPLER2D_AUTOREG(s_LightMapTexture);

#define TIME ViewPositionAndTime.w
#define AWAN_SPEED 0.04
#define AWAN_SX 1.0
#define SISI_HALUS 0.15
#define AWAN_SPEED_DINDING 0.04
#define AWAN_SZ 1.0

#define BAYANG_AWAN 1
#define BAYANG_AWAN_TINGGI 40.0
#define BAYANG_AWAN_KUAT 0.38
#define BAYANG_AWAN_DITHER 0.02

#define saturate(x) clamp(x, 0.0, 1.0)

#define END_BRIGHT 4.0
#define END_SAT 0.7
#define END_LM_TINT 0.35
#define END_SHOULDER 0.7
#define END_BLOCKLIGHT 0.55
#define END_LIGHT_COL vec3(1.1, 0.9, 1.55)
#define END_LIGHT_AMT 0.85
#define END_DIM 0.55

#define GLOW_INTENSITY 0.85
#define GLOW_SIANG 0.35
#define GLOW_HALO 0.30
#define GLOW_HALO_JARAK 6.0
#define GLOW_DENYUT 0.08

#define GLOW_MODE 1
#define GLOW_LANGIT_MIN 0.55
#define GLOW_LANGIT_MAX 0.90
#define GLOW_OBOR_MIN 0.30
#define GLOW_OBOR_MAX 0.75

const vec3 HALO_TAP[8] = vec3[8](
    vec3(1.0, 0.0, 1.0),
    vec3(-1.0, 0.0, 1.0),
    vec3(0.0, 1.0, 1.0),
    vec3(0.0, -1.0, 1.0),
    vec3(1.0, 1.0, 0.6),
    vec3(-1.0, 1.0, 0.6),
    vec3(1.0, -1.0, 0.6),
    vec3(-1.0, -1.0, 0.6)
);

vec3 ACESFilm(vec3 x) {
    float a = 3.0;
    float b = 0.001;
    float c = 3.1;
    float d = 0.48;
    float e = 0.1;
    return ((x * (a * x + b)) / (x * (c * x + d) + e));
}

vec3 Tm(vec3 x) {
    float a = 4.1;
    float b = 0.01;
    float c = 2.6;
    float d = 0.56;
    float e = 0.4;

    return (x * (a * x + b)) / (x * (c * x + d) + e);
}

vec3 saturation(vec3 rgb, float adjustment) {
    const vec3 W = vec3(0.2125, 0.7154, 0.0721);
    vec3 intensity = vec3(dot(rgb, W));
    return mix(intensity, rgb, adjustment);
}

bool endFog(vec4 f) {
    return (
        f.r > f.g &&
        f.b > f.g &&
        f.r < 0.05 &&
        f.b < 0.05 &&
        f.g < 0.05
    );
}

float hashAwan(vec2 q) {
    return fract(sin(dot(q, vec2(127.1, 311.7))) * 43758.5453);
}

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

    return mix(a, b, u.x)
        + (c - a) * u.y * (1.0 - u.x)
        + (d - b) * u.x * u.y;
}

/*
 * Catatan:
 * Fungsi vorolit pada source GLSL asli menggunakan macro F
 * yang perlu diperiksa kembali apabila shaderc menolaknya.
 */
float vorolit(vec2 uv) {
    uv.xy -= TIME * 0.02;
    uv *= 3.0;

    vec4 v = vec4(0.0);
    v.xy = uv * (sin(TIME * 0.2) + 1.5) * 0.5;
    v.w = TIME * 0.3;

    return noise(uv + v.xy);
}

const vec3 RAY_D = vec3(
    0.324617,
    -0.927478,
    0.185496
);

const vec3 RAY_T1 = vec3(
    -0.943858,
    -0.330350,
    0.0
);

const vec3 RAY_T2 = vec3(
    0.061279,
    -0.175082,
    -0.982645
);

float causticRay(vec3 pp, vec3 nn) {
    vec2 q = vec2(
        dot(pp, RAY_T1),
        dot(pp, RAY_T2)
    );

    float c = smoothstep(
        0.2,
        1.0,
        vorolit(q * 0.35)
    );

    c *= mix(
        0.6,
        1.0,
        saturate(dot(nn, -RAY_D))
    );

    return c;
}

float godRayFog(vec3 pp, vec3 ww) {
    float jit = fract(
        dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))
        * 52.9829189
    );

    float acc = 0.0;

    for (int i = 0; i < 4; i++) {
        float t = (float(i) + jit) * 0.25;

        vec3 sp = pp - ww * (1.0 - t);

        vec2 q = vec2(
            dot(sp, RAY_T1),
            dot(sp, RAY_T2)
        );

        acc += smoothstep(
            0.45,
            0.8,
            noise(q * 0.9)
        );
    }

    return acc * 0.25;
}

vec3 fresnel(vec3 f0, float NdotV) {
    return f0 + (1.0 - f0) * pow(1.0 - NdotV, 5.0);
}

float awanRef(vec3 awR, float spd) {
    vec2 awBase = vec2(
        AWAN_SX * awR.x,
        AWAN_SZ * awR.z
    ) / max(awR.y, 0.02);

    float awDh = hashAwan(awBase * 999.0);
    float awShd = 0.0;
    float awMm = 1.0;
    float awSc = 1.4;

    for (int awI = 0; awI < 12; awI++) {
        awSc /= 1.009;
        awMm *= 0.96;

        float awAwan = step(
            0.88,
            hashAwan(
                floor(
                    awBase * awSc +
                    awDh * 0.02 +
                    TIME * spd
                )
            )
        );

        awShd = mix(awShd, awMm, awAwan);
    }

    return awShd * smoothstep(0.0, 0.12, awR.y);
}

float awanBayang(vec2 awBase) {
    float awDh = hashAwan(awBase * 999.0);

    return step(
        0.88,
        hashAwan(
            floor(
                awBase * 1.33 +
                awDh * BAYANG_AWAN_DITHER +
                TIME * AWAN_SPEED
            )
        )
    );
}

float genangan(vec2 q) {
    vec2 g = q / 4.0;
    vec2 id = floor(g);
    vec2 f = fract(g) - 0.5;

    vec2 c = (
        vec2(hash(id + 7.3), hash(id + 13.1)) - 0.5
    ) * 0.2;

    vec2 hs = 0.15 + 0.17 * vec2(
        hash(id + 3.7),
        hash(id + 9.9)
    );

    vec2 d = abs(f - c) - (hs - 0.1);

    float sd = length(max(d, 0.0))
        + min(max(d.x, d.y), 0.0)
        - 0.1;

    return step(0.7, hash(id))
        * (1.0 - smoothstep(-0.04, 0.04, sd));
}

void main() {

#if defined(DEPTH_ONLY) || defined(DEPTH_ONLY_OPAQUE) || defined(DEPTH_ONLY_ALPHA_TEST)

    #if defined(ALPHA_TEST) || defined(DEPTH_ONLY_ALPHA_TEST)
        vec4 depthDiffuse = texture2D(s_MatTexture, v_texcoord0);

        if (depthDiffuse.a < 0.5) {
            discard;
        }
    #endif

    gl_FragColor = vec4_splat(0.0);
    return;

#else

    #if defined(ALPHA_TEST)
        if (texture2D(s_MatTexture, v_texcoord0).a < 0.5) {
            discard;
        }
    #endif

    vec4 _811 = v_color0;
    vec2 _671 = v_ditheringAndMaskTinting;
    vec4 _552 = texture2D(s_MatTexture, v_texcoord0);

    vec4 oreTex = texture2DLod(
        s_MatTexture,
        v_texcoord0,
        0.0
    );

    int oreCode = int(oreTex.w * 255.0 + 0.5);

    float oreGlow = (
        oreCode == 252
    ) ? 1.0 : (
        (oreCode == 253) ? 0.4 : 0.0
    );

    if (v_ditheringAndMaskTinting.y > 0.5) {
        oreGlow = 0.0;
    }

    vec4 color = v_color0;

    vec3 N = normalize(cross(dFdx(p.xyz), dFdy(p.xyz)));
    vec3 V = normalize(-wp);

    float NdotV = max(0.001, dot(N, V));
    float lenKs = length(-ks);
    float lenWp = length(wp);

    if (_671.y > 0.5) {

        vec3 _588 = mix(
            _552.xyz,
            _552.xyz * v_color0.xyz,
            vec3(_552.w)
        ).xyz * _811.w;

        _552 = vec4(
            _588.x,
            _588.y,
            _588.z,
            _552.w
        );

        _552.w = 1.0;

    } else {

        vec3 _aoMul = v_color0.xyz;

        if (abs(v_color0.g - v_color0.r) < 0.002) {

            float ao = mix(
                0.55,
                0.8,
                smoothstep(0.3, 0.7, v_color0.g)
            );

            float terbuka = smoothstep(
                0.55,
                0.9,
                v_lightmapUV.y
            );

            terbuka = max(
                terbuka,
                step(fctl.x, 0.00999) *
                (endFog(v_fog) ? 0.0 : 1.0)
            );

            ao = mix(0.8, ao, terbuka);
            _aoMul = vec3(ao);
        }

        vec3 _596 = _552.xyz * _aoMul;

        _552 = vec4(
            _596.x,
            _596.y,
            _596.z,
            _552.w
        );

        _552.w = _811.w;
    }

    vec4 _666 = v_fog;
    bool _akhirE = endFog(_666);

    vec2 _lmUV = v_lightmapUV;

    if (_akhirE) {
        _lmUV.x *= END_BLOCKLIGHT;
    }

    vec3 _lm = texture2D(s_LightMapTexture, _lmUV).xyz;

    if (_akhirE) {

        float _lmL = dot(
            _lm,
            vec3(0.2125, 0.7154, 0.0721)
        );

        _lm = mix(
            vec3(_lmL),
            _lm,
            END_LM_TINT
        );

        _lm = mix(
            _lm,
            _lmL * END_LIGHT_COL,
            saturate(v_lightmapUV.x) * END_LIGHT_AMT
        );
    }

    vec4 _finalColor = vec4(
        mix(
            (_lm * _552.xyz),
            FogColor.xyz,
            vec3(_666.w)
        ),
        _552.w
    );

    vec2 uv1 = v_lightmapUV;
    vec4 fclr = v_fog;
    float Apa = 0.6;

    float goa = smoothstep(0.5, 0.0, uv1.y);

    float wk = texture2D(
        s_LightMapTexture,
        vec2(0.0, 1.0)
    ).r;

    wk = clamp((wk - 0.5) * 2.0, 0.0, 1.0);

    float siangd = wk * uv1.y;
    float malamd = 1.0 - wk * uv1.y;
    float sored = 0.5 - abs(0.5 - wk) * uv1.y;

    vec2 rf = vec2(
        0.5 + 20.0 / FogAndDistanceControl.z,
        1.0
    );

    vec2 rt = clamp(
        (FogAndDistanceControl.xy - rf) /
        (vec2(0.23, 0.7) - rf),
        0.0,
        1.0
    );

    float rk = rt.x * rt.y;
    float hujand = rk * rk * (3.0 - 2.0 * rk);
    float dudan = smoothstep(0.55, 0.1, fctl.r) * uv1.y;

    bool nether = (
        fclr.r > fclr.b &&
        fclr.r < 0.5 &&
        fclr.b < 0.2
    );

    bool udw = (fctl.x < 0.01) && !endFog(fclr);
    bool akhir = endFog(fclr);

    float endK = (fctl.x < 0.01) ? END_DIM : 1.0;

    float isnether =
        step(fclr.b, fclr.r) *
        step(fclr.r, 0.4999) *
        step(fclr.b, 0.1999);

    float isudw = step(fctl.x, 0.00999) *
        (akhir ? 0.0 : 1.0);

    float isakhir = akhir ? 1.0 : 0.0;

    // Directional lighting
    float dir = max(N.x, -N.z);

    _finalColor.xyz *= mix(
        vec3(0.75),
        vec3(0.65),
        dir *
        (1.0 - pow(v_lightmapUV.x * 0.9, 4.7)) *
        (1.0 - goa) *
        (1.0 - hujand) *
        (1.0 - malamd)
    );

    // Tonemapping and saturation
    if (!akhir) {

        _finalColor.rgb *= vec3(1.7);

        _finalColor.rgb = saturation(
            _finalColor.rgb,
            1.2
        );

        if (!udw) {
            _finalColor.xyz *= ACESFilm(_finalColor.xyz);
        }

        _finalColor.xyz = Tm(_finalColor.xyz);

        if (color.a == 0.0) {
            _finalColor.rgb *= 1.1;
        }
    }

    // Shadow
    float shadowRaw = smoothstep(1.0, 0.95, uv1.y);
    float shadow = shadowRaw;

    float nolight = 1.0 - uv1.x;
    shadow *= nolight;

    shadow *=
        (1.0 - hujand) *
        (1.0 - hujand) *
        (1.0 - hujand) *
        (1.0 - hujand) *
        (1.0 - hujand) *
        (1.0 - malamd) *
        (1.0 - malamd) *
        (1.0 - malamd) *
        (1.0 - malamd) *
        (1.0 - malamd);

    shadow = mix(0.0, 0.65, shadow);

    _finalColor.rgb = mix(
        _finalColor.rgb,
        vec3(0.0, 0.0, 0.12),
        shadow
    );

    // Cloud shadows
    #if BAYANG_AWAN

    if (N.y > -0.5) {

        float bAktif =
            smoothstep(0.05, 0.3, wk) *
            (1.0 - hujand) *
            (1.0 - isudw) *
            (1.0 - isnether) *
            (1.0 - isakhir) *
            (1.0 - shadowRaw);

        if (bAktif > 0.001) {

            float bDy = BAYANG_AWAN_TINGGI /
                sqrt(
                    dot(wp.xz, wp.xz) +
                    BAYANG_AWAN_TINGGI *
                    BAYANG_AWAN_TINGGI
                );

            float bMask =
                awanBayang(wp.xz / BAYANG_AWAN_TINGGI) *
                smoothstep(0.0, 0.12, bDy);

            _finalColor.rgb = mix(
                _finalColor.rgb,
                vec3(0.0, 0.0, 0.12),
                BAYANG_AWAN_KUAT * bMask * bAktif
            );
        }
    }

    #endif

    // Metallic
    bool metalic = (oreCode == 250);

    if (v_ditheringAndMaskTinting.y > 0.5) {
        metalic = false;
    }

    float metalAktif =
        smoothstep(0.05, 0.3, wk) *
        (1.0 - hujand) *
        (1.0 - isudw) *
        (1.0 - isnether) *
        (1.0 - isakhir) *
        (1.0 - shadow / 0.65);

    if (metalic && metalAktif > 0.001) {

        vec3 sebelumMetal = _finalColor.rgb;

        vec3 fr = fresnel(
            vec3(0.3),
            NdotV
        );

        // Floor reflection
        if (N.y > 0.5) {

            _finalColor.rgb = mix(
                _finalColor.rgb,
                mix(
                    _finalColor.rgb,
                    mix(
                        vec3(0.1, 0.5, 1.0) * 1.2,
                        vec3(1.1),
                        lenKs / 70.0
                    ),
                    fr
                ),
                siangd * (1.0 - hujand)
            );

            _finalColor.rgb = mix(
                _finalColor.rgb,
                mix(
                    _finalColor.rgb,
                    mix(
                        vec3(0.1, 0.5, 1.0) * 1.2,
                        vec3(1.0, 0.8, 0.1),
                        lenKs / 70.0
                    ),
                    fr
                ),
                sored * (1.0 - hujand)
            );

            if (!udw) {

                vec3 awR = reflect(-V, N);

                if (awR.y > 0.0) {

                    float awMask = awanRef(
                        awR,
                        0.04
                    );

                    vec3 awHasil = mix(
                        _finalColor.rgb,
                        vec3(1.0),
                        awMask
                    );

                    _finalColor.rgb = mix(
                        _finalColor.rgb,
                        awHasil,
                        fr
                    );
                }
            }
        }

        // Wall reflection
        if (max(abs(N.x), abs(N.z)) > 0.5) {

            float awEl = wp.y / max(lenWp, 0.001);

            float awAtas = smoothstep(
                -SISI_HALUS,
                SISI_HALUS,
                awEl
            );

            vec3 sisiAtas = mix(
                vec3(0.1, 0.5, 1.0) * 1.2,
                vec3(1.1),
                clamp(
                    length(wp.xz) / max(abs(wp.y), 0.1) * 0.1,
                    0.05,
                    0.7
                )
            );

            vec3 sisiBawah = vec3(212.0, 232.0, 255.0) / 255.0;

            _finalColor.rgb = mix(
                _finalColor.rgb,
                mix(sisiBawah, sisiAtas, awAtas),
                fr
            );

            vec3 soreAtas = vec3(0.1, 0.5, 1.0) * 1.2;
            vec3 soreBawah = vec3(1.0, 0.8, 0.1);

            _finalColor.rgb = mix(
                _finalColor.rgb,
                mix(soreBawah, soreAtas, awAtas),
                fr * sored * (1.0 - hujand)
            );
        }

        // Wall cloud reflection
        if (wp.y > -0.05 && !udw) {

            bool awX = abs(N.x) > 0.5;
            bool awZ = abs(N.z) > 0.5;

            if (awX || awZ) {

                vec3 awR = reflect(-V, N);

                if (awR.y > 0.0) {

                    float awMask = awanRef(
                        awR,
                        AWAN_SPEED_DINDING
                    );

                    if (awX) {

                        vec3 awHasil = mix(
                            _finalColor.rgb,
                            vec3(1.0),
                            awMask
                        );

                        _finalColor.rgb = mix(
                            _finalColor.rgb,
                            awHasil,
                            fr
                        );
                    }

                    if (awZ) {

                        vec3 awHasil = mix(
                            _finalColor.rgb,
                            vec3(1.0),
                            awMask
                        );

                        _finalColor.rgb = mix(
                            _finalColor.rgb,
                            awHasil,
                            fr
                        );
                    }
                }
            }
        }

        _finalColor.rgb = mix(
            sebelumMetal,
            _finalColor.rgb,
            metalAktif
        );
    }

    // Rain and puddles
    if (!(udw && nether && akhir)) {

        _finalColor.rgb +=
            vec3(-0.2, -0.1, -0.05) *
            dudan *
            (1.0 - malamd);

        if (hujand > 0.0) {

            float langitTerbuka = smoothstep(
                0.88,
                0.94,
                uv1.y
            );

            float gn = genangan(p.xz) *
                langitTerbuka *
                hujand;

            vec3 gnBasah = mix(
                _finalColor.rgb,
                vec3(dot(_finalColor.rgb, vec3(0.333))),
                0.4
            ) * 0.7;

            vec3 gnKilap = mix(
                gnBasah,
                FogColor.rgb * 1.2 + 0.1,
                fresnel(vec3(0.15), NdotV)
            );

            _finalColor.rgb = mix(
                _finalColor.rgb,
                gnKilap,
                gn
            );
        }
    }

    // Block light glow
    if (!akhir) {

        float jitB = fract(
            52.9829189 *
            fract(
                dot(
                    gl_FragCoord.xy,
                    vec2(0.06711056, 0.00583715)
                )
            )
        );

        float Lb = uv1.x + (jitB - 0.5) * 0.0625;

        float core = smoothstep(0.72, 0.94, Lb);
        core *= core;

        float night = 1.0 - siangd * 0.8;

        float flick = 0.9 + 0.1 * sin(
            TIME * 3.0 +
            p.x * 0.7 +
            p.z * 0.7
        );

        float glow = core *
            night *
            flick *
            (1.0 - saturate(lenWp / 80.0));

        _finalColor.rgb +=
            vec3(1.0, 0.55, 0.2) *
            glow *
            0.6 *
            (1.0 - saturate(_finalColor.rgb));
    }

    // Underwater
    if (udw && !(nether && akhir)) {

        _finalColor.rgb *= vec3(0.3, 1.1, 0.85);

        _finalColor = mix(
            _finalColor,
            v_fog * sqrt(vec4(0.8, 1.5, 1.4, 1.0)),
            Apa * saturate(lenKs / 50.0)
        );

        _finalColor.rgb +=
            _finalColor.rgb *
            (vec3(0.0, 0.7, 1.0) * 1.15) *
            pow(v_lightmapUV.x * 0.5, 4.7);

        _finalColor.xyz *= ACESFilm(_finalColor.xyz);

        // Caustics
        float skyMax = 0.9375;

        float topBand = smoothstep(
            skyMax - 0.01,
            skyMax - 0.001,
            uv1.y
        );

        float belowEye = 1.0 - smoothstep(
            0.7,
            1.2,
            wp.y
        );

        float inWater = mix(
            1.0,
            belowEye,
            topBand
        );

        float lit = smoothstep(
            skyMax - 0.24,
            skyMax - 0.02,
            uv1.y
        );

        float cmask = inWater *
            lit *
            (1.0 - saturate(lenKs / 20.0));

        if (cmask > 0.001) {

            _finalColor.rgb +=
                vec3(0.6, 0.9, 1.0) *
                causticRay(p, N) *
                0.35 *
                cmask;
        }

        // Underwater god rays
        if (wk > 0.05) {

            float fog2 = smoothstep(
                1.0,
                2.0,
                lenWp
            );

            if (fog2 > 0.01) {

                float dens = godRayFog(p, wp);

                float bukanBawah = smoothstep(
                    -0.7,
                    -0.2,
                    N.y
                );

                float a = dens *
                    fog2 *
                    wk *
                    uv1.y *
                    0.5 *
                    bukanBawah;

                _finalColor.rgb = mix(
                    _finalColor.rgb,
                    vec3(0.5, 1.05, 1.2),
                    saturate(a)
                );
            }
        }
    }

    // Nether
    if (nether) {

        _finalColor.rgb *=
            vec3(1.5, 0.9, 0.0) *
            (1.0 - sored);

        _finalColor.rgb += vec3(0.2);

        _finalColor.rgb = Tm(_finalColor.rgb) * 0.9;

        vec3 fogn = mix(
            _finalColor.rgb,
            fclr.rgb * vec3(1.5, 1.0, 0.0),
            clamp(
                lenKs / FogAndDistanceControl.z * 1.5,
                0.1,
                0.2
            )
        );

        _finalColor.rgb = fogn;
    }

    // Glowing ore
    float oreFade = 1.0 - smoothstep(
        0.6,
        1.0,
        lenKs / FogAndDistanceControl.z
    );

    float oreNaungan = 1.0;

    #if GLOW_MODE == 1

        oreNaungan = 1.0 - smoothstep(
            GLOW_LANGIT_MIN,
            GLOW_LANGIT_MAX,
            v_lightmapUV.y
        );

    #elif GLOW_MODE == 2

        oreNaungan = (
            1.0 - smoothstep(
                GLOW_LANGIT_MIN,
                GLOW_LANGIT_MAX,
                v_lightmapUV.y
            )
        ) * (
            1.0 - smoothstep(
                GLOW_OBOR_MIN,
                GLOW_OBOR_MAX,
                v_lightmapUV.x
            )
        );

    #endif

    float oreTerang = mix(
        GLOW_SIANG,
        1.0,
        malamd
    ) * oreNaungan;

    float orePulse = 1.0 +
        GLOW_DENYUT *
        sin(TIME * 1.5);

    float oreK = oreTerang *
        oreFade *
        orePulse;

    if (oreGlow > 0.0) {

        _finalColor.rgb +=
            oreTex.rgb *
            oreGlow *
            GLOW_INTENSITY *
            oreK;

    } else if (
        GLOW_HALO > 0.0 &&
        oreNaungan > 0.01 &&
        v_ditheringAndMaskTinting.y < 0.5 &&
        lenKs < GLOW_HALO_JARAK
    ) {

        vec2 tx = 1.0 / vec2(
            textureSize(s_MatTexture, 0)
        );

        float hSum = 0.0;
        vec3 hCol = vec3(0.0);

        for (int i = 0; i < 8; i++) {

            vec4 t = texture2DLod(
                s_MatTexture,
                v_texcoord0 + HALO_TAP[i].xy * tx,
                0.0
            );

            int c = int(t.w * 255.0 + 0.5);

            float g = (
                c == 252
            ) ? 1.0 : (
                (c == 253) ? 0.4 : 0.0
            );

            hSum += g * HALO_TAP[i].z;
            hCol += t.rgb * (g * HALO_TAP[i].z);
        }

        if (hSum > 0.0) {

            hCol /= hSum;

            _finalColor.rgb +=
                hCol *
                saturate(hSum * 0.35) *
                GLOW_HALO *
                oreK;
        }
    }

    // End dimension
    if (akhir) {

        vec3 ec = _finalColor.rgb *
            END_BRIGHT *
            endK;

        ec = saturation(ec, END_SAT);

        vec3 eo = max(
            ec - END_SHOULDER,
            0.0
        );

        float ek = 1.0 - END_SHOULDER;

        ec = min(ec, vec3(END_SHOULDER))
            + ek * (1.0 - exp(-eo / ek));

        _finalColor.rgb = ec;

        float drl = max(
            max(-N.x, -N.z),
            -N.y
        );

        _finalColor.xyz *= mix(
            vec3(0.95),
            vec3(0.5),
            drl * (1.0 - v_lightmapUV.x)
        );
    }

    // Fog
    if (!akhir) {

        vec4 fgst = mix(
            _finalColor,
            mix(
                _finalColor,
                v_fog * sqrt(vec4(1.1, 1.1, 1.1, 1.0)),
                Apa * saturate(lenKs / 100.0)
            ),
            (1.0 - hujand)
        );

        _finalColor = fgst;

        _finalColor = mix(
            _finalColor,
            mix(
                _finalColor,
                v_fog * sqrt(vec4(0.8, 0.8, 0.8, 1.0)),
                Apa * saturate(lenKs / 20.0)
            ),
            dudan
        );
    }

    gl_FragColor = _finalColor;

#endif
}
