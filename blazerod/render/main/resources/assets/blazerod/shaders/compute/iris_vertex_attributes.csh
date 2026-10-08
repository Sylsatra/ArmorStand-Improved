#blazerod_version version(<4.3) ? 150 : 430
#blazerod_extension version(<4.3); GL_ARB_shader_storage_buffer_object : require
#blazerod_extension version(<4.3); GL_ARB_compute_shader: require
#blazerod_extension version(<4.3); GL_ARB_shading_language_packing: require

#ifndef COMPUTE_LOCAL_SIZE
#error COMPUTE_LOCAL_SIZE not defined
#endif// COMPUTE_LOCAL_SIZE

struct SourceVertex {
    vec3 position;
    uint color;
    vec2 uv0;
    uint uv1;
    uint uv2;
    uint normal;
};

struct TargetVertex {
    vec3 position;
    uint color;
    vec2 uv0;
    uint uv1;
    uint uv2;
    uint normal;
    uint iris_Entity0;
    uint iris_Entity1;
    float mc_midTexCoordU;
    float mc_midTexCoordV;
    uint at_tangent;
};

layout(std430) readonly buffer SourceVertexData {
    SourceVertex[] SourceVertices;
};

layout(std430) buffer TargetVertexData {
    TargetVertex[] TargetVertices;
};

layout(std430) readonly buffer IrisTriangleIndicesData {
    uint IrisTriangleIndices[];
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

bool finiteFloat(float value) {
    return !isnan(value) && !isinf(value);
}

bool finiteVec3(vec3 value) {
    return !any(isnan(value)) && !any(isinf(value));
}

uint packTangent(vec3 tangent, float handedness) {
    ivec4 tangentBytes = ivec4(clamp(vec4(tangent, handedness), -1.0, 1.0) * 127.0);
    return (uint(tangentBytes.x) & 255u)
        | ((uint(tangentBytes.y) & 255u) << 8u)
        | ((uint(tangentBytes.z) & 255u) << 16u)
        | ((uint(tangentBytes.w) & 255u) << 24u);
}

vec2 safeMidUv(vec2 uv) {
    return vec2(finiteFloat(uv.x) ? uv.x : 0.0, finiteFloat(uv.y) ? uv.y : 0.0);
}

TargetVertex copyVertex(SourceVertex source) {
    TargetVertex target;
    target.position = source.position;
    target.color = source.color;
    target.uv0 = source.uv0;
    target.uv1 = source.uv1;
    target.uv2 = source.uv2;
    target.normal = source.normal;
    target.iris_Entity0 = 0u;
    target.iris_Entity1 = 0u;
    target.mc_midTexCoordU = 0.0;
    target.mc_midTexCoordV = 0.0;
    target.at_tangent = 0u;
    return target;
}

TargetVertex writeIrisAttributes(
    TargetVertex vertex,
    vec2 midUv,
    vec3 tangent,
    vec3 bitangent,
    vec3 generatedNormal
) {
    vertex.iris_Entity0 = IrisEntity0;
    vertex.iris_Entity1 = IrisEntity1;

    vec3 normal = unpackSnorm4x8(vertex.normal).xyz;
#ifdef GENERATE_NORMALS
    float generatedNormalLengthSquared = dot(generatedNormal, generatedNormal);
    if (finiteFloat(generatedNormalLengthSquared) && generatedNormalLengthSquared >= 1e-8) {
        normal = normalize(generatedNormal);
        vertex.normal = packSnorm4x8(vec4(normal, 0.0));
    }
#endif// GENERATE_NORMALS
    float normalLengthSquared = dot(normal, normal);
    if (!finiteFloat(normalLengthSquared) || normalLengthSquared < 1e-8) {
        normal = vec3(0.0, 1.0, 0.0);
    } else {
        normal = normalize(normal);
    }

    tangent -= normal * dot(tangent, normal);
    float tangentLengthSquared = dot(tangent, tangent);
    if (!finiteFloat(tangentLengthSquared) || tangentLengthSquared < 1e-8) {
        tangent = abs(normal.y) < 0.999
            ? vec3(normal.z, 0.0, -normal.x)
            : vec3(0.0, -normal.z, normal.y);
    }
    tangent = normalize(tangent);
    float handedness = dot(cross(tangent, normal), bitangent) < 0.0 ? -1.0 : 1.0;

    vertex.mc_midTexCoordU = midUv.x;
    vertex.mc_midTexCoordV = midUv.y;
    vertex.at_tangent = packTangent(tangent, handedness);
    return vertex;
}

void main() {
    uint workItem = gl_GlobalInvocationID.x;
    if (IrisExpanded == 0u) {
        if (workItem >= TotalVerticesCount) {
            return;
        }
        TargetVertex vertex = copyVertex(SourceVertices[IrisTriangleIndices[workItem]]);
        TargetVertices[workItem] = writeIrisAttributes(
            vertex,
            safeMidUv(vertex.uv0),
            vec3(0.0),
            vec3(0.0),
            vec3(0.0)
        );
        return;
    }

    uint triangleStart = workItem * 3u;
    if (triangleStart + 2u >= TotalVerticesCount) {
        return;
    }

    uint source0 = IrisTriangleIndices[triangleStart];
    uint source1 = IrisTriangleIndices[triangleStart + 1u];
    uint source2 = IrisTriangleIndices[triangleStart + 2u];
    TargetVertex vertex0 = copyVertex(SourceVertices[source0]);
    TargetVertex vertex1 = copyVertex(SourceVertices[source1]);
    TargetVertex vertex2 = copyVertex(SourceVertices[source2]);

    vec2 midUv = safeMidUv(vertex0.uv0);
    float candidateU = (vertex0.uv0.x + vertex1.uv0.x + vertex2.uv0.x) / 3.0;
    float candidateV = (vertex0.uv0.y + vertex1.uv0.y + vertex2.uv0.y) / 3.0;
    bool validMidUv = finiteFloat(candidateU) && finiteFloat(candidateV);
    if (validMidUv) {
        midUv = vec2(candidateU, candidateV);
    }

    vec3 edge1 = (ModelTangentMatrix * vec4(vertex1.position - vertex0.position, 0.0)).xyz;
    vec3 edge2 = (ModelTangentMatrix * vec4(vertex2.position - vertex0.position, 0.0)).xyz;
    float du1 = vertex1.uv0.x - vertex0.uv0.x;
    float dv1 = vertex1.uv0.y - vertex0.uv0.y;
    float du2 = vertex2.uv0.x - vertex0.uv0.x;
    float dv2 = vertex2.uv0.y - vertex0.uv0.y;
    float determinant = du1 * dv2 - du2 * dv1;
    vec3 faceNormal = cross(edge1, edge2);
    float area = length(faceNormal);
    vec3 generatedNormal = finiteFloat(area) && area >= 1e-8 ? faceNormal : vec3(0.0);
    vec3 tangent = vec3(0.0);
    vec3 bitangent = vec3(0.0);
    if (finiteFloat(determinant) && abs(determinant) >= 1e-8 && finiteFloat(area) && area >= 1e-8) {
        float weight = area / determinant;
        vec3 faceTangent = (edge1 * dv2 - edge2 * dv1) * weight;
        vec3 faceBitangent = (edge2 * du1 - edge1 * du2) * weight;
        if (finiteVec3(faceTangent) && finiteVec3(faceBitangent)) {
            tangent = faceTangent;
            bitangent = faceBitangent;
        }
    }

    TargetVertices[triangleStart] = writeIrisAttributes(
        vertex0,
        validMidUv ? midUv : safeMidUv(vertex0.uv0),
        tangent,
        bitangent,
        generatedNormal
    );
    TargetVertices[triangleStart + 1u] = writeIrisAttributes(
        vertex1,
        validMidUv ? midUv : safeMidUv(vertex1.uv0),
        tangent,
        bitangent,
        generatedNormal
    );
    TargetVertices[triangleStart + 2u] = writeIrisAttributes(
        vertex2,
        validMidUv ? midUv : safeMidUv(vertex2.uv0),
        tangent,
        bitangent,
        generatedNormal
    );
}
