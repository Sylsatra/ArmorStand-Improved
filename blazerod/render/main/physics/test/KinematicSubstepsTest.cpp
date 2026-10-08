#include "blazerod/render/main/physics/PhysicsWorld.h"

#include <array>
#include <cmath>
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace blazerod::physics {

// Observe the real Bullet pre-tick state after saveKinematicState(), rather than
// testing a second implementation of the interpolation math.
struct PhysicsWorldTestAccess {
    static btDiscreteDynamicsWorld& World(PhysicsWorld& physics) { return *physics.world; }
    static btRigidBody& Body(PhysicsWorld& physics) { return *physics.rigidbodies[0].rigidbody; }
};

namespace {
constexpr float kInterval = 1.0f / 30.0f;
constexpr float kFixedStep = 1.0f / 120.0f;

void Require(bool condition, const std::string& message) {
    if (!condition) throw std::runtime_error(message);
}

void Near(btScalar actual, btScalar expected, const std::string& message, btScalar tolerance = 0.002f) {
    Require(std::isfinite(actual) && std::abs(actual - expected) <= tolerance,
            message + ": expected " + std::to_string(expected) + ", got " + std::to_string(actual));
}

void NearVector(const btVector3& actual, const btVector3& expected, const std::string& message) {
    for (int axis = 0; axis < 3; ++axis) Near(actual[axis], expected[axis], message);
}

struct Sample {
    btTransform transform;
    btVector3 linear_velocity;
    btVector3 angular_velocity;
    btScalar timestep;
};

struct Capture {
    btRigidBody* body;
    std::vector<Sample> samples;

    static void Tick(btDynamicsWorld* world, btScalar timestep) {
        auto& capture = *static_cast<Capture*>(world->getWorldUserInfo());
        const auto& body = *capture.body;
        capture.samples.push_back({body.getWorldTransform(), body.getLinearVelocity(),
                                   body.getAngularVelocity(), timestep});
    }
};

std::array<unsigned char, 84> BodyData(float offset_x) {
    std::array<unsigned char, 84> data{};
    auto put = [&](size_t offset, auto value) { std::memcpy(data.data() + offset, &value, sizeof(value)); };
    put(0, uint32_t(1));
    put(4, uint32_t(0xffff));
    put(8, uint32_t(ShapeType::SPHERE));
    put(12, uint32_t(PhysicsMode::FOLLOW_BONE));
    put(16, 0.5f);
    put(28, offset_x);
    put(32, 10.0f);
    put(68, 0.5f);
    put(80, 0.03f);
    return data;
}

struct Fixture {
    std::array<unsigned char, 84> data;
    PhysicsScene scene;
    std::array<float, 16> initial_transform = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 10, 0, 1};
    PhysicsWorld physics;
    Capture capture;

    explicit Fixture(float offset_x = 0)
        : data(BodyData(offset_x)), scene(data.size(), data.data(), 0, nullptr),
          physics(scene, initial_transform.size(), initial_transform.data()),
          capture{&PhysicsWorldTestAccess::Body(physics), {}} {
        PhysicsWorldTestAccess::World(physics).setInternalTickCallback(Capture::Tick, &capture, true);
    }

    void Target(float x, const btQuaternion& rotation = btQuaternion(0, 0, 0, 1)) {
        float* buffer = physics.GetTransformBuffer();
        buffer[0] = x;
        buffer[1] = 10;
        buffer[2] = 0;
        for (int axis = 0; axis < 4; ++axis) buffer[3 + axis] = rotation[axis];
    }

    void Step(float interval = kInterval) {
        capture.samples.clear();
        physics.Step(interval, 4, kFixedStep);
    }
};

void TranslationAndFrameBoundary() {
    Fixture fixture;
    fixture.Target(4);
    fixture.Step();
    Require(fixture.capture.samples.size() == 4, "Four native substeps must execute");
    for (size_t i = 0; i < 4; ++i) {
        const auto& sample = fixture.capture.samples[i];
        NearVector(sample.transform.getOrigin(), btVector3(i + 1, 10, 0), "Interpolated collider position");
        NearVector(sample.linear_velocity, btVector3(4 / kInterval, 0, 0), "Constant contact velocity");
        NearVector(sample.angular_velocity, btVector3(0, 0, 0), "Translation has no angular velocity");
        Near(sample.timestep, kFixedStep, "Substep interval");
    }
    Near(fixture.physics.GetTransformBuffer()[0], 4, "FOLLOW_BONE preserves animated input");

    fixture.Target(6);
    fixture.Step(kInterval / 2);
    Require(fixture.capture.samples.size() == 2, "Two native substeps must execute");
    for (size_t i = 0; i < 2; ++i) {
        Near(fixture.capture.samples[i].transform.getOrigin().x(), 5 + i, "Next frame continues from last pose");
        Near(fixture.capture.samples[i].linear_velocity.x(), 4 / kInterval, "Velocity across frame boundary");
    }

    fixture.Step();
    for (const auto& sample : fixture.capture.samples) {
        NearVector(sample.linear_velocity, btVector3(0, 0, 0), "Stopped collider has no residual velocity");
    }
}

void RotationAndQuaternionSign() {
    Fixture fixture;
    const btQuaternion target(btVector3(0, 0, 1), 0.8f);
    fixture.Target(0, target);
    fixture.Step();
    Require(fixture.capture.samples.size() == 4, "Rotation executes four native substeps");
    for (size_t i = 0; i < 4; ++i) {
        const auto& sample = fixture.capture.samples[i];
        const btQuaternion expected(btVector3(0, 0, 1), 0.2f * (i + 1));
        Near(std::abs(sample.transform.getRotation().dot(expected)), 1, "Interpolated rotation");
        NearVector(sample.angular_velocity, btVector3(0, 0, 0.8f / kInterval), "Constant angular contact velocity");
    }

    // Animation may return the opposite quaternion for the same orientation.
    fixture.Target(0, btQuaternion(-target.x(), -target.y(), -target.z(), -target.w()));
    fixture.Step();
    for (const auto& sample : fixture.capture.samples) {
        NearVector(sample.angular_velocity, btVector3(0, 0, 0), "Equivalent quaternion causes no spin");
    }
}

void BoneOffsetAndReset() {
    Fixture fixture(2);
    fixture.Target(4);
    fixture.Step();
    Require(fixture.capture.samples.size() == 4, "Offset collider executes four native substeps");
    for (size_t i = 0; i < 4; ++i) {
        Near(fixture.capture.samples[i].transform.getOrigin().x(), 3 + i, "PMX collider offset is retained");
    }

    fixture.physics.ResetRigidBody(0, 100, 10, 0, 0, 0, 0, 1);
    fixture.Step();
    for (const auto& sample : fixture.capture.samples) {
        Near(sample.transform.getOrigin().x(), 102, "Reset retains bone offset");
        NearVector(sample.linear_velocity, btVector3(0, 0, 0), "Reset avoids teleport contact velocity");
    }
}
}  // namespace
}  // namespace blazerod::physics

int main() {
    try {
        blazerod::physics::TranslationAndFrameBoundary();
        blazerod::physics::RotationAndQuaternionSign();
        blazerod::physics::BoneOffsetAndReset();
        std::cout << "Kinematic collider substep regressions passed\n";
        return 0;
    } catch (const std::exception& exception) {
        std::cerr << exception.what() << '\n';
        return 1;
    }
}
