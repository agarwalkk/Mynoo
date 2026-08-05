import matplotlib.pyplot as plt
import matplotlib.patches as patches

fig, ax = plt.subplots(figsize=(7, 3.5))
ax.set_aspect('equal')
ax.axis('off')

# Ground Line
ax.plot([-0.5, 6.5], [0, 0], color='#64748B', lw=2, ls='--')

# Fulcrum Triangle
fulcrum_x = 2.0
fulcrum = patches.Polygon([[fulcrum_x - 0.3, 0], [fulcrum_x + 0.3, 0], [fulcrum_x, 0.6]], 
                          closed=True, facecolor='#10B981', edgecolor='#047857', lw=2)
ax.add_patch(fulcrum)
ax.text(fulcrum_x, -0.25, 'Fulcrum', ha='center', fontsize=11, weight='bold', color='#047857')

# Lever Beam
beam_y = 0.65
beam = patches.Rectangle((0, beam_y), 6.0, 0.1, facecolor='#F59E0B', edgecolor='#B45309', lw=2)
ax.add_patch(beam)

# Load (Left Side)
load_box = patches.Rectangle((0.2, beam_y + 0.1), 0.6, 0.6, facecolor='#EF4444', edgecolor='#991B1B', lw=2)
ax.add_patch(load_box)
ax.text(0.5, beam_y + 0.4, 'Load\n($F_2$)', ha='center', va='center', color='white', weight='bold', fontsize=10)

# Effort Arrow (Right Side)
effort_x = 5.5
ax.annotate('', xy=(effort_x, beam_y + 0.1), xytext=(effort_x, beam_y + 1.1),
            arrowprops=dict(arrowstyle='->', color='#2563EB', lw=3, mutation_scale=20))
ax.text(effort_x, beam_y + 1.25, 'Effort ($F_1$)', ha='center', color='#2563EB', weight='bold', fontsize=11)

# Arm Dimension Labels
# Load Arm
ax.annotate('', xy=(0.5, beam_y - 0.2), xytext=(fulcrum_x, beam_y - 0.2),
            arrowprops=dict(arrowstyle='<->', color='#334155', lw=1.5))
ax.text(1.25, beam_y - 0.4, 'Load Arm ($d_2$)', ha='center', fontsize=10, color='#334155')

# Effort Arm
ax.annotate('', xy=(fulcrum_x, beam_y - 0.2), xytext=(effort_x, beam_y - 0.2),
            arrowprops=dict(arrowstyle='<->', color='#334155', lw=1.5))
ax.text(3.75, beam_y - 0.4, 'Effort Arm ($d_1$)', ha='center', fontsize=10, color='#334155')

ax.set_xlim(-0.8, 6.8)
ax.set_ylim(-0.6, 2.2)
plt.title("Lever Principle ($F_1 \\times d_1 = F_2 \\times d_2$)", fontsize=13, weight='bold')

plt.tight_layout()
plt.savefig("lever_system_q.svg", format="svg", bbox_inches='tight')
plt.show()