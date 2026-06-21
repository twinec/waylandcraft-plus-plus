#version 150

uniform sampler2D sampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
	vec4 color = texture(sampler, texCoord);
	color.rgb /= color.a;
	fragColor = color;
}
