import matplotlib.pyplot as plt
import matplotlib.patches as patches

fig, ax = plt.subplots(figsize=(6, 5))
ax.set_aspect('equal')
ax.axis('off')

# 1. Cell Membrane (Outer irregular shape or ellipse)
cell_membrane = patches.Ellipse((0, 0), width=6, height=4.5, angle=10,
                                facecolor='#FEF08A', edgecolor='#CA8A04', lw=3, alpha=0.6)
ax.add_patch(cell_membrane)
ax.text(-2.2, 1.8, 'Cell Membrane', fontsize=10, color='#854D0E', weight='bold')

# 2. Cytoplasm Background Label
ax.text(-1.5, -1.5, 'Cytoplasm', fontsize=10, color='#A16207', style='italic')

# 3. Nucleus (Double Circle)
nucleus_outer = patches.Circle((0.5, 0.2), 1.0, facecolor='#60A5FA', edgecolor='#1D4ED8', lw=2)
nucleus_inner = patches.Circle((0.5, 0.2), 0.4, facecolor='#1E40AF', edgecolor='#1E3A8A')
ax.add_patch(nucleus_outer)
ax.add_patch(nucleus_inner)

# Label A pointing to Nucleus
ax.annotate('A: Nucleus', xy=(0.5, 0.2), xytext=(2.2, 1.5),
            arrowprops=dict(arrowstyle='->', color='#DC2626', lw=2),
            fontsize=11, color='#DC2626', weight='bold')

# 4. Mitochondrion (Oval with inner cristae folds)
mitochondria = patches.Ellipse((-1.8, 0.5), width=1.0, height=0.5, angle=-25,
                               facecolor='#F87171', edgecolor='#B91C1C', lw=1.5)
ax.add_patch(mitochondria)

# Label B pointing to Mitochondria
ax.annotate('B: Mitochondrion', xy=(-1.8, 0.5), xytext=(-3.2, -0.8),
            arrowprops=dict(arrowstyle='->', color='#DC2626', lw=2),
            fontsize=11, color='#DC2626', weight='bold')

# Limits & Export
ax.set_xlim(-3.8, 3.8)
ax.set_ylim(-2.5, 2.5)

plt.title("Schematic of an Animal Cell Structure", fontsize=12, weight='bold', pad=10)
plt.tight_layout()
plt.savefig("animal_cell_diagram.svg", format="svg", bbox_inches='tight')
plt.show()