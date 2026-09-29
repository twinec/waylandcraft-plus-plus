#version 330

uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
	vec4 color = texture(Sampler0, texCoord);
	color.rgb /= color.a;
	fragColor = color;
}
