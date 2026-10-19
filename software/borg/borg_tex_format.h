// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
// GENERATED from hardware/borg/src/TexFormat.scala by
//   mill hardware.borg.runMain borg.EmitTexFormatHeader

#pragma once

// Texel format codes for a texture descriptor, word 2 [19:14] (docs/B2_texture_unit.md)
#define BORG_TEX_FORMAT_R8_UNORM                     1
#define BORG_TEX_FORMAT_R8_SNORM                     2
#define BORG_TEX_FORMAT_R8_UINT                      3
#define BORG_TEX_FORMAT_R8_SINT                      4
#define BORG_TEX_FORMAT_R8_SRGB                      5
#define BORG_TEX_FORMAT_R8G8_UNORM                   6
#define BORG_TEX_FORMAT_R8G8_SNORM                   7
#define BORG_TEX_FORMAT_R8G8_UINT                    8
#define BORG_TEX_FORMAT_R8G8_SINT                    9
#define BORG_TEX_FORMAT_R8G8_SRGB                    10
#define BORG_TEX_FORMAT_R8G8B8A8_UNORM               11
#define BORG_TEX_FORMAT_R8G8B8A8_SNORM               12
#define BORG_TEX_FORMAT_R8G8B8A8_UINT                13
#define BORG_TEX_FORMAT_R8G8B8A8_SINT                14
#define BORG_TEX_FORMAT_R8G8B8A8_SRGB                15
#define BORG_TEX_FORMAT_B8G8R8A8_UNORM               16
#define BORG_TEX_FORMAT_B8G8R8A8_SRGB                17
#define BORG_TEX_FORMAT_R16_UNORM                    18
#define BORG_TEX_FORMAT_R16_SNORM                    19
#define BORG_TEX_FORMAT_R16_UINT                     20
#define BORG_TEX_FORMAT_R16_SINT                     21
#define BORG_TEX_FORMAT_R16_SFLOAT                   22
#define BORG_TEX_FORMAT_R16G16_UNORM                 23
#define BORG_TEX_FORMAT_R16G16_SNORM                 24
#define BORG_TEX_FORMAT_R16G16_UINT                  25
#define BORG_TEX_FORMAT_R16G16_SINT                  26
#define BORG_TEX_FORMAT_R16G16_SFLOAT                27
#define BORG_TEX_FORMAT_R16G16B16A16_UNORM           28
#define BORG_TEX_FORMAT_R16G16B16A16_SNORM           29
#define BORG_TEX_FORMAT_R16G16B16A16_UINT            30
#define BORG_TEX_FORMAT_R16G16B16A16_SINT            31
#define BORG_TEX_FORMAT_R16G16B16A16_SFLOAT          32
#define BORG_TEX_FORMAT_R32_UINT                     33
#define BORG_TEX_FORMAT_R32_SINT                     34
#define BORG_TEX_FORMAT_R32_SFLOAT                   35
#define BORG_TEX_FORMAT_R32G32_UINT                  36
#define BORG_TEX_FORMAT_R32G32_SINT                  37
#define BORG_TEX_FORMAT_R32G32_SFLOAT                38
#define BORG_TEX_FORMAT_R32G32B32A32_UINT            39
#define BORG_TEX_FORMAT_R32G32B32A32_SINT            40
#define BORG_TEX_FORMAT_R32G32B32A32_SFLOAT          41
#define BORG_TEX_FORMAT_A2B10G10R10_UNORM_PACK32     42
#define BORG_TEX_FORMAT_A2B10G10R10_UINT_PACK32      43
#define BORG_TEX_FORMAT_R5G6B5_UNORM_PACK16          44
#define BORG_TEX_FORMAT_A1R5G5B5_UNORM_PACK16        45
#define BORG_TEX_FORMAT_B4G4R4A4_UNORM_PACK16        46
#define BORG_TEX_FORMAT_B10G11R11_UFLOAT_PACK32      47
#define BORG_TEX_FORMAT_E5B9G9R9_UFLOAT_PACK32       48
#define BORG_TEX_FORMAT_D16_UNORM                    49
#define BORG_TEX_FORMAT_X8_D24_UNORM_PACK32          50
#define BORG_TEX_FORMAT_D32_SFLOAT                   51
