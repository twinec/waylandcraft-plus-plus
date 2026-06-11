package dev.evvie.waylandcraft.math;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3d;
import org.joml.Vector3d;

import net.minecraft.world.phys.Vec3;

// Math helper object representing plane in 3d space
public class WorldPlane {
	
	public final Vec3 origin;
	public final Vec3 localX;
	public final Vec3 localY;
	public final Vec3 localZ;
	
	public WorldPlane(Vec3 origin, Vec3 localX, Vec3 localY, Vec3 localZ) {
		this.origin = origin;
		this.localX = localX;
		this.localY = localY;
		this.localZ = localZ;
	}
	
	public Vec3 localToWorld(double x, double y, double z) {
		return origin.add(localX.scale(x)).add(localY.scale(y)).add(localZ.scale(z));
	}
	
	public Vec3 worldToLocal(Vec3 in) {
		Vec3 relative = in.subtract(origin);
		
		Matrix3d matrix = new Matrix3d(
			localX.x, localX.y, localX.z, // Column 0
			localY.x, localY.y, localY.z, // Column 1
			localZ.x, localZ.y, localZ.z  // Column 2
		);
		matrix.invert();
		
		Vector3d result = matrix.transform(new Vector3d(relative.x, relative.y, relative.z));
		return new Vec3(result.x, result.y, result.z);
	}
	
	public @Nullable Intersection intersect(Vec3 pos, Vec3 dir) {
		double p1 = origin.subtract(pos).dot(localZ);
		double p2 = dir.dot(localZ);
		
		// Avoid division by zero
		if(p2 == 0) return null;
		
		double t = p1 / p2;
		
		// Intersection happens behind the camera
		if(t < 0) return null;
		
		Vec3 hitPos = pos.add(dir.scale(t));
		Vec3 localCoords = worldToLocal(hitPos);
		
		double dist = t;
		
		// Flip distance depending on the side of the plane
		if(p2 > 0) dist *= -1;
		
		return new Intersection(hitPos, localCoords, dist);
	}
	
	public static record Intersection(Vec3 world, Vec3 local, double dist) {
	}
	
}
