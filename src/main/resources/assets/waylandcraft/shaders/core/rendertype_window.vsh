#version 330
#extension GL_ARB_separate_shader_objects : require
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;

layout(location = 0) out vec2 texCoord0;

void main() {
	gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
	
	texCoord0 = UV0;
}
