#blazerod_version version(<4.3) ? 150 : 430
#blazerod_extension version(<4.3); GL_ARB_shader_storage_buffer_object : require
#blazerod_extension version(<4.3); GL_ARB_compute_shader: require
#blazerod_extension version(<4.3); GL_ARB_shading_language_packing: require

#if defined(IRIS_VERTEX_FORMAT) && !defined(IRIS_DIRECT_VERTEX_FORMAT)
layout(std430) buffer IrisTriangleIndicesData {
    uint IrisTriangleIndices[];
};
#endif// IRIS_VERTEX_FORMAT

#ifdef IRIS_SOURCE_NORMALS
#define WITH_NORMAL
layout(std430) buffer IrisSourceNormalsData {
    float IrisSourceNormals[];
};
#endif// IRIS_SOURCE_NORMALS

#moj_import <blazerod:joint.glsl>
#moj_import <blazerod:morph.glsl>

#ifndef COMPUTE_LOCAL_SIZE
#error COMPUTE_LOCAL_SIZE not defined
#endif// COMPUTE_LOCAL_SIZE

#if INPUT_MATERIAL == 0
#ifdef SKINNED
struct SourceVertex {
    vec3 position;
    uint color;// vec4 of ubyte
    vec2 uv0;
    uvec2 joint;// vec4 of ushort
    vec4 weight;
};
#else// SKINNED
struct SourceVertex {
    vec3 position;
    uint color;// vec4 of ubyte
    vec2 uv0;
};
#endif
#elif INPUT_MATERIAL == 1
#error PBR material is not supported
#elif INPUT_MATERIAL == 2
#ifndef WITH_NORMAL
#define WITH_NORMAL
#endif// WITH_NORMAL
#ifdef SKINNED
struct SourceVertex {
    vec3 position;
    uint color;// vec4 of ubyte
    vec2 uv0;
    uint normal;// vec3 of byte, plus 1 byte of padding
    uvec2 joint;// vec4 of ushort
    vec4 weight;
};
#else// SKINNED
struct SourceVertex {
    vec3 position;
    uint color;// vec4 of ubyte
    vec2 uv0;
    uint normal;// vec3 of byte, plus 1 byte of padding
};
#endif
#else// INPUT_MATERIAL
#error Unknown material or undefined INPUT_MATERIAL
#endif// INPUT_MATERIAL

layout(std430) buffer SourceVertexData {
    SourceVertex[] SourceVertices;
};

#ifndef IRIS_VERTEX_FORMAT
struct TargetVertex {
    vec3 position;
    uint color;// vec4 of ubyte
    vec2 uv0;
    uint uv1;// vec2 of short
    uint uv2;// vec2 of short
    uint normal;// vec3 of byte, plus 1 byte of padding
};
#else// IRIS_VERTEX_FORMAT
struct TargetVertex {
    vec3 position;
    uint color;// vec4 of byte
    vec2 uv0;
    uint uv1;// vec2 of short
    uint uv2;// vec2 of short
    uint normal;// vec3 of byte, plus 1 byte of padding
    uint iris_Entity0;
    uint iris_Entity1;
    float mc_midTexCoordU;
    float mc_midTexCoordV;
    int at_tangent;// vec4 of byte
};
#endif// IRIS_VERTEX_FORMAT

layout(std430) buffer TargetVertexData {
    TargetVertex[] TargetVertices;
};

layout(std140) uniform ComputeData {
    mat4 ModelNormalMatrix;
    uint TotalVerticesCount;
    uint UV1;
    uint UV2;
    mat4 ModelTangentMatrix;
    uint IrisEntity0;
    uint IrisEntity1;
    uint IrisExpanded;
};

layout(local_size_x = COMPUTE_LOCAL_SIZE, local_size_y = 1, local_size_z = 1) in;

void main() {
    uint vertexId = gl_GlobalInvocationID.x;
    if (vertexId >= TotalVerticesCount) {
        return;
    }

    uint sourceVertexId = vertexId;
#if defined(IRIS_VERTEX_FORMAT) && !defined(IRIS_DIRECT_VERTEX_FORMAT)
    sourceVertexId = IrisTriangleIndices[vertexId];
#endif// IRIS_VERTEX_FORMAT
    SourceVertex sourceVertex = SourceVertices[sourceVertexId];
    vec3 finalPosition = sourceVertex.position;
    vec4 finalColor = unpackUnorm4x8(uint(sourceVertex.color));
    #ifdef IRIS_SOURCE_NORMALS
    vec3 sourceNormal = vec3(
        IrisSourceNormals[sourceVertexId * 3u],
        IrisSourceNormals[sourceVertexId * 3u + 1u],
        IrisSourceNormals[sourceVertexId * 3u + 2u]
    );
    float sourceNormalLengthSquared = dot(sourceNormal, sourceNormal);
    vec3 finalNormal = sourceNormalLengthSquared > 1e-12 && !isnan(sourceNormalLengthSquared) && !isinf(sourceNormalLengthSquared)
        ? sourceNormal * inversesqrt(sourceNormalLengthSquared)
        : vec3(0, 1, 0);
    #elif defined(WITH_NORMAL)
    vec3 finalNormal = normalize(unpackSnorm4x8(uint(sourceVertex.normal)).xyz);
    #else
    vec3 finalNormal = vec3(0, 1, 0);
    #endif

    vec2 finalTexCoord = sourceVertex.uv0;

    finalPosition = GET_MORPHED_VERTEX_POSITION(finalPosition);
    finalColor = GET_MORPHED_VERTEX_COLOR(finalColor);

    #ifdef SKINNED
    ivec4 jointIndices = ivec4(
    sourceVertex.joint.x & 0xFFFFu,
    (sourceVertex.joint.x >> 16) & 0xFFFFu,
    sourceVertex.joint.y & 0xFFFFu,
    (sourceVertex.joint.y >> 16) & 0xFFFFu
    );
    finalPosition = skinPositionTransform(vec4(finalPosition, 1.0), sourceVertex.weight, jointIndices).xyz;

    #ifdef WITH_NORMAL
    finalNormal = skinNormalTransform(finalNormal, sourceVertex.weight, jointIndices);
    #endif// WITH_NORMAL
    #endif// SKINNED

    #ifdef WITH_NORMAL
    finalNormal = mat3(ModelNormalMatrix) * finalNormal;
    float finalNormalLengthSquared = dot(finalNormal, finalNormal);
    finalNormal = finalNormalLengthSquared > 1e-12 && !isnan(finalNormalLengthSquared) && !isinf(finalNormalLengthSquared)
        ? finalNormal * inversesqrt(finalNormalLengthSquared)
        : vec3(0, 1, 0);
    #endif// WITH_NORMAL

    finalTexCoord = GET_MORPHED_VERTEX_TEX_COORD(finalTexCoord);

    TargetVertex targetVertex;
    targetVertex.position = finalPosition;
    targetVertex.color = packUnorm4x8(finalColor);
    targetVertex.uv0 = finalTexCoord;
    targetVertex.uv1 = UV1;
    targetVertex.uv2 = UV2;
    targetVertex.normal = packSnorm4x8(vec4(finalNormal, 0));

    #ifdef IRIS_VERTEX_FORMAT
    #ifdef IRIS_DIRECT_VERTEX_FORMAT
    vec3 tangent = abs(finalNormal.y) < 0.999
        ? vec3(finalNormal.z, 0.0, -finalNormal.x)
        : vec3(0.0, -finalNormal.z, finalNormal.y);
    targetVertex.iris_Entity0 = IrisEntity0;
    targetVertex.iris_Entity1 = IrisEntity1;
    // Unlit BlazeRod materials have no normal-map input. Their indexed
    // vertices can stay shared, so UV itself is a safe mid-UV fallback.
    targetVertex.mc_midTexCoordU = finalTexCoord.x;
    targetVertex.mc_midTexCoordV = finalTexCoord.y;
    targetVertex.at_tangent = int(packSnorm4x8(vec4(tangent, 1.0)));
    #else
    targetVertex.iris_Entity0 = 0u;
    targetVertex.iris_Entity1 = 0u;
    targetVertex.mc_midTexCoordU = 0.0;
    targetVertex.mc_midTexCoordV = 0.0;
    targetVertex.at_tangent = 0;
    #endif
    #endif// IRIS_VERTEX_FORMAT

    TargetVertices[vertexId] = targetVertex;
}
