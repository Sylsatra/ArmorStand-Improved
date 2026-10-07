#blazerod_version version(<4.3) ? 150 : 430
#blazerod_extension version(<4.3); GL_ARB_shader_storage_buffer_object : require
#blazerod_extension version(<4.3); GL_ARB_compute_shader: require
#blazerod_extension version(<4.3); GL_ARB_shading_language_packing: require

#ifndef COMPUTE_LOCAL_SIZE
#error COMPUTE_LOCAL_SIZE not defined
#endif// COMPUTE_LOCAL_SIZE

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

bool finiteFloat(float value) {
    return !isnan(value) && !isinf(value);
}

bool finiteVec3(vec3 value) {
    return !any(isnan(value)) && !any(isinf(value));
}

uint packTangent(vec3 tangent, float handedness) {
    ivec4 packed = ivec4(clamp(vec4(tangent, handedness), -1.0, 1.0) * 127.0);
    return (uint(packed.x) & 255u)
        | ((uint(packed.y) & 255u) << 8u)
        | ((uint(packed.z) & 255u) << 16u)
        | ((uint(packed.w) & 255u) << 24u);
}

void main() {
    uint vertexId = gl_GlobalInvocationID.x;
    if (vertexId >= TotalVerticesCount) {
        return;
    }

    TargetVertex vertex = TargetVertices[vertexId];
    vertex.iris_Entity0 = IrisEntity0;
    vertex.iris_Entity1 = IrisEntity1;

    float midU = finiteFloat(vertex.uv0.x) ? vertex.uv0.x : 0.0;
    float midV = finiteFloat(vertex.uv0.y) ? vertex.uv0.y : 0.0;
    vec3 tangent = vec3(0.0);
    vec3 bitangent = vec3(0.0);
    vec3 generatedNormal = vec3(0.0);

    if (IrisExpanded != 0u) {
        uint triangleStart = (vertexId / 3u) * 3u;
        uint corner = vertexId % 3u;
        uint firstId = triangleStart + ((corner + 1u) % 3u);
        uint secondId = triangleStart + ((corner + 2u) % 3u);
        TargetVertex first = TargetVertices[firstId];
        TargetVertex second = TargetVertices[secondId];

        float candidateU = (vertex.uv0.x + first.uv0.x + second.uv0.x) / 3.0;
        float candidateV = (vertex.uv0.y + first.uv0.y + second.uv0.y) / 3.0;
        if (finiteFloat(candidateU) && finiteFloat(candidateV)) {
            midU = candidateU;
            midV = candidateV;
        }

        vec3 edge1 = first.position - vertex.position;
        vec3 edge2 = second.position - vertex.position;
        edge1 = (ModelTangentMatrix * vec4(edge1, 0.0)).xyz;
        edge2 = (ModelTangentMatrix * vec4(edge2, 0.0)).xyz;
        float du1 = first.uv0.x - vertex.uv0.x;
        float dv1 = first.uv0.y - vertex.uv0.y;
        float du2 = second.uv0.x - vertex.uv0.x;
        float dv2 = second.uv0.y - vertex.uv0.y;
        float determinant = du1 * dv2 - du2 * dv1;
        vec3 faceNormal = cross(edge1, edge2);
        float area = length(faceNormal);
        if (finiteFloat(area) && area >= 1e-8) {
            generatedNormal = faceNormal;
        }
        if (finiteFloat(determinant) && abs(determinant) >= 1e-8 && finiteFloat(area) && area >= 1e-8) {
            float weight = area / determinant;
            vec3 faceTangent = (edge1 * dv2 - edge2 * dv1) * weight;
            vec3 faceBitangent = (edge2 * du1 - edge1 * du2) * weight;
            if (finiteVec3(faceTangent) && finiteVec3(faceBitangent)) {
                tangent = faceTangent;
                bitangent = faceBitangent;
            }
        }
    }

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

    vertex.mc_midTexCoordU = midU;
    vertex.mc_midTexCoordV = midV;
    vertex.at_tangent = packTangent(tangent, handedness);
    TargetVertices[vertexId] = vertex;
}
