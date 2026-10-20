# Create ArUco marker DICT_4X4_50 ID 23 using pure Python (no OpenCV)
# ArUco 4x4 marker structure: 4x4 data bits + 1-bit border
# ID 23 in binary (16 bits for 4x4): 0000000000010111

marker_id = 23
size = 4  # 4x4 data bits
border = 1  # 1 bit border

# Generate the 16-bit binary representation
bits = [(marker_id >> i) & 1 for i in range(15, -1, -1)]

# Create 6x6 matrix (4x4 data + 1 border on each side)
matrix = [[1 for _ in range(size + 2*border)] for _ in range(size + 2*border)]

# Fill data bits
for i in range(size):
    for j in range(size):
        matrix[border + i][border + j] = bits[i * size + j]

# Scale up to 200x200 pixels (each bit = 200/6 ≈ 33 pixels)
scale = 33
img_size = scale * (size + 2*border)  # 198 pixels

# Create PGM (portable graymap) format
with open('aruco_id23.pgm', 'w') as f:
    f.write(f"P2\n{img_size} {img_size}\n1\n")
    for row in matrix:
        for val in row:
            for _ in range(scale):
                f.write(f"{val} ")
        for _ in range(scale):
            f.write("\n")

print(f"Created {img_size}x{img_size} PGM marker")
